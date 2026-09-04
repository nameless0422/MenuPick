# 빌드 스테이지. 이미지 태그는 패치까지 고정한다 — 떠 있는 태그는 같은 Dockerfile로도
# 매번 다른 결과를 만들어, 롤백했는데 다르게 동작하는 상황을 만든다.
# jsdom 30과 그 전이 의존성은 Node 22 계열에서 최소 22.22.2를 요구한다. 더 낮은
# 버전도 당장은 설치되지만 EBADENGINE 상태라 다음 의존성 갱신에서 빌드가 깨질 수 있다.
# 정적 번들은 아키텍처와 무관하다. 멀티아키텍처 배포에서 이 단계를 TARGETPLATFORM으로
# 실행하면 arm64용 npm/tsc/vite가 QEMU 위에서 돌아 이미지 게시가 장시간 멈춘다.
# 빌드 호스트에서 한 번만 네이티브로 만들고, 아래 nginx 런타임만 대상별로 조립한다.
FROM --platform=$BUILDPLATFORM node:22.22.2-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# API 주소는 빌드 시점에 번들로 구워진다. ENV로만 넘기면 커밋된 frontend/.env
# (VITE_API_BASE_URL=http://localhost:8080)와 우선순위를 두고 다투므로, .env.production을
# 직접 만들어 덮어쓴다 — production 모드에서 .env보다 우선한다.
# 기본값이 빈 문자열이라 상대경로(같은 오리진의 nginx)로 나간다.
#
# OAuth 값은 여기 없다. 인가 URL을 백엔드가 조립하므로 client_id·redirect_uri가 번들에
# 들어갈 일이 없다 — 넣으면 그대로 공개된다(src/auth/oauthUrls.ts 참고).
# 카카오맵 JS 키는 반대로 여기에 들어가는 게 맞다. JS SDK는 브라우저가 카카오에 직접 붙어
# 키를 숨길 방법이 없고, 카카오 콘솔의 사이트 도메인 화이트리스트가 대신 보호한다
# (REST API 키와는 다른 값 — 자세한 구분은 src/maps/kakaoSdk.ts).
# 기본값이 빈 문자열이라 키를 안 넘겨도 빌드는 통과하고, 지도 자리에 안내만 뜬다.
ARG VITE_API_BASE_URL=""
ARG VITE_KAKAO_JS_KEY=""
RUN echo "VITE_API_BASE_URL=${VITE_API_BASE_URL}" > .env.production
RUN echo "VITE_KAKAO_JS_KEY=${VITE_KAKAO_JS_KEY}" >> .env.production

RUN npm run build

FROM nginx:1.27.3-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 기본 nginx.conf의 events 블록은 worker_connections가 1024다. HTTP/2 부하 테스트에서
# 클라이언트 연결과 upstream 연결이 이 한도를 함께 소비해, 앱보다 nginx가 먼저
# "worker_connections are not enough"로 요청을 거절했다. 컨테이너의 open-files 한도는
# 65535이므로 4096은 그 범위 안에서 충분한 여유를 주면서 무제한으로 키우지 않는 값이다.
RUN sed -i 's/worker_connections  *1024;/worker_connections  4096;/' /etc/nginx/nginx.conf \
    && grep -q 'worker_connections  4096;' /etc/nginx/nginx.conf

# localhost가 아니라 127.0.0.1을 쓴다 — 컨테이너 안에서 localhost는 ::1로 풀리는데
# nginx.conf가 `listen 80`이라 IPv4에만 바인딩되어 healthcheck가 항상 실패한다.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1
