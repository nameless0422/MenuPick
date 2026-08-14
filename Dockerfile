# --- build stage ---
# 태그를 마이너까지 고정한다. `17-jdk`처럼 떠 있는 태그는 같은 Dockerfile로도 매번 다른 이미지를
# 만들어내 "내 머신에서는 되는데" 류의 재현 불가 빌드를 만든다.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# --- runtime stage ---
FROM eclipse-temurin:17-jre-jammy

# HEALTHCHECK용 curl. jre-jammy 베이스에는 wget/curl이 모두 없다.
# 이미지가 수 MB 늘어나지만, 오케스트레이터가 앱의 살아있음을 판별할 수 없는 편이 더 비싸다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 컨테이너 탈출 시 피해를 줄이기 위해 non-root로 실행한다.
RUN groupadd --system --gid 1001 menupick \
    && useradd --system --uid 1001 --gid menupick --no-create-home menupick

WORKDIR /app
COPY --from=build --chown=menupick:menupick /workspace/build/libs/*.jar app.jar

USER menupick

EXPOSE 8080

# 컨테이너 메모리 한도(compose의 mem_limit)를 JVM이 인식해 힙 상한을 잡게 한다.
# 지정하지 않으면 기본 MaxRAMPercentage(25%)로 힙을 너무 작게 잡거나,
# cgroup 인식 실패 시 호스트 전체 메모리 기준으로 잡아 OOMKill을 유발한다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

# actuator health는 SecurityConfig에서 permitAll이므로 인증 없이 조회 가능하다.
# start-period는 Spring Boot 기동 시간(마이그레이션 포함)을 감안한 값.
#
# MANAGEMENT_SERVER_PORT가 있으면 actuator는 서비스 포트가 아니라 그 포트에 있다(prod).
# 앱(application-prod.yml)과 이 헬스체크가 같은 변수를 읽으므로 둘이 갈릴 수 없다.
# 값이 없으면(dev/local) actuator는 서비스 포트에 그대로 있다.
# 관리 포트는 컨테이너 루프백에만 바인딩되지만, 이 curl은 컨테이너 안에서 돌아 그대로 닿는다.
# localhost가 아니라 127.0.0.1을 쓰는 이유: 컨테이너의 /etc/hosts는 localhost를 ::1로도 풀고,
# 관리 포트는 IPv4 루프백에만 바인딩돼 있어 IPv6를 먼저 시도하면 헛걸음이 한 번 생긴다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-8080}/actuator/health" || exit 1
