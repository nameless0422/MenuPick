# 빌드 스테이지. 이미지 태그는 패치까지 고정한다 — 떠 있는 태그는 같은 Dockerfile로도
# 매번 다른 결과를 만들어, 롤백했는데 다르게 동작하는 상황을 만든다.
FROM node:22.12.0-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# API 주소는 빌드 시점에 번들로 구워진다. ENV로만 넘기면 커밋된 frontend/.env
# (VITE_API_BASE_URL=http://localhost:8080)와 우선순위를 두고 다투므로, .env.production을
# 직접 만들어 덮어쓴다 — production 모드에서 .env보다 우선한다.
# 기본값이 빈 문자열이라 상대경로(같은 오리진의 nginx)로 나간다.
ARG VITE_API_BASE_URL=""
ARG VITE_KAKAO_CLIENT_ID=""
ARG VITE_KAKAO_REDIRECT_URI=""
ARG VITE_GOOGLE_CLIENT_ID=""
ARG VITE_GOOGLE_REDIRECT_URI=""
RUN { \
      echo "VITE_API_BASE_URL=${VITE_API_BASE_URL}"; \
      echo "VITE_KAKAO_CLIENT_ID=${VITE_KAKAO_CLIENT_ID}"; \
      echo "VITE_KAKAO_REDIRECT_URI=${VITE_KAKAO_REDIRECT_URI}"; \
      echo "VITE_GOOGLE_CLIENT_ID=${VITE_GOOGLE_CLIENT_ID}"; \
      echo "VITE_GOOGLE_REDIRECT_URI=${VITE_GOOGLE_REDIRECT_URI}"; \
    } > .env.production

RUN npm run build

FROM nginx:1.27.3-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

# localhost가 아니라 127.0.0.1을 쓴다 — 컨테이너 안에서 localhost는 ::1로 풀리는데
# nginx.conf가 `listen 80`이라 IPv4에만 바인딩되어 healthcheck가 항상 실패한다.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1
