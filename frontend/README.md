# 메뉴픽 프론트엔드

React + Vite + TypeScript. 스택 선택 근거는 [../docs/DecisionLog.md D-025](../docs/DecisionLog.md)를 참고.

## 시작하기

```bash
npm install
cp .env .env.local   # 카카오/구글 클라이언트 ID를 .env.local에 채운다 (gitignore됨)
npm run dev           # http://localhost:5173
```

백엔드는 `local` 프로파일로 별도 기동해야 한다 (`../src`, `./gradlew bootRun`). 백엔드가 CORS로
`http://localhost:5173`을 허용해두었다(`application-local.yml`의 `cors.allowed-origins`).

## OAuth 리다이렉트 URI 맞추기 (중요)

카카오/구글 로그인은 **프론트가 authorize URL로 리다이렉트 → 콜백 페이지가 `code`를 받아 백엔드로 전달**
하는 구조다. 이때 프론트가 authorize 요청에 쓰는 `redirect_uri`와, 백엔드가 토큰 교환 시 쓰는
`redirect_uri`(`KAKAO_REDIRECT_URI`/`GOOGLE_REDIRECT_URI` env)가 **정확히 같아야** 한다. 아래 세 곳이 모두 일치해야 한다:

1. `.env.local`의 `VITE_KAKAO_REDIRECT_URI` / `VITE_GOOGLE_REDIRECT_URI`
2. 백엔드 `KAKAO_REDIRECT_URI` / `GOOGLE_REDIRECT_URI` env
3. 카카오/구글 개발자 콘솔에 등록된 Redirect URI

로컬 기본값은 `http://localhost:5173/oauth/kakao/callback`, `http://localhost:5173/oauth/google/callback`.

## 인증 흐름

- Access Token은 메모리에만 보관한다(브라우저 저장소에 안 씀 — XSS 대비). 새로고침하면 사라지므로,
  앱 부팅 시(`AuthContext`) `/api/v1/auth/refresh`를 한 번 호출해 HttpOnly 쿠키로 조용히 재로그인한다.
- Axios 응답 인터셉터가 401을 만나면 refresh를 한 번 시도하고 원 요청을 재시도한다(`src/api/http.ts`).

## 폴더 구조

```
src/
  api/          axios 클라이언트, 인증 API 함수
  auth/         AuthContext, OAuth authorize URL 빌더
  routes/       페이지 컴포넌트 + 라우팅
```

## 구현 현황

로그인(카카오/구글) ~ 세션 복원까지는 동작한다. 메뉴/식당/픽/히스토리 페이지는 자리만 잡아둔 상태(TODO 주석 참고) — [Requirements.md](../docs/Requirements.md)의 유저 스토리(US-2~US-5) 기준으로 채워나가면 된다.
