# --- build stage ---
# 태그를 마이너까지 고정한다. `17-jdk`처럼 떠 있는 태그는 같은 Dockerfile로도 매번 다른 이미지를
# 만들어내 "내 머신에서는 되는데" 류의 재현 불가 빌드를 만든다.
#
# --platform=$BUILDPLATFORM: 이 단계는 타깃 아키텍처가 아니라 "빌드를 돌리는 머신"의
# 아키텍처로 고정한다. 멀티아치(amd64+arm64) 빌드에서 이게 없으면 buildx가 타깃마다
# 이 단계를 한 번씩, 그것도 QEMU 에뮬레이션으로 돌린다 — 에뮬레이트된 JVM 위에서 Gradle을
# 돌리는 셈이라 arm64 빌드만 수십 분씩 걸린다.
# 안전한 이유는 산출물이 순수 바이트코드 fat jar라 아키텍처 독립이기 때문이다(빌드 대상에
# 네이티브 분류자 의존성이 없다). 아키텍처 차이는 런타임 단계의 JRE 베이스 이미지가 흡수한다.
# 네이티브 라이브러리를 끌어오는 의존성이 생기면 이 전제가 깨지므로 그때 재검토할 것.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk-jammy AS build
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
#
# 힙이 찼을 때 JVM의 기본 동작은 "죽지 않는 것"이다. GC가 계속 돌며 거의 아무것도 회수하지 못하는
# 상태(GC 데스 스파이럴)로 들어가면 프로세스는 살아 있고 응답만 안 하는데, 이 조합에서는 아무도
# 복구하지 못한다: compose의 restart: unless-stopped는 프로세스가 죽어야 개입하고,
# plain Docker/compose는 unhealthy 컨테이너를 재시작하지 않는다(그건 Swarm의 동작이다).
# 결과적으로 서비스는 멎어 있는데 컨테이너는 Up으로 보인다.
#   +ExitOnOutOfMemoryError — 여기서 핵심이다. OOM 발생 즉시 프로세스를 끝내 restart 정책이
#     컨테이너를 살려내게 한다. 이게 없으면 위 두 안전망이 전부 무력하다.
#   +HeapDumpOnOutOfMemoryError / HeapDumpPath — 재시작이 증상을 덮어버리므로, 덮이기 전에
#     원인 분석용 스냅샷을 남긴다. /tmp에 두는 이유는 non-root(menupick)가 쓸 수 있는 경로이고
#     컨테이너 파일시스템이라 재시작하면 사라지기 때문이다 — 보존이 필요하면 발생 직후
#     `docker cp menupick-app:/tmp/heapdump.hprof .`로 꺼낼 것. (힙 크기만 한 파일이 생긴다)
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+HeapDumpOnOutOfMemoryError", \
    "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-jar", "app.jar"]

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
