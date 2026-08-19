# 메뉴픽 프론트엔드

React + Vite + TypeScript. 스택 선택 근거는 [../docs/DecisionLog.md D-025](../docs/DecisionLog.md)를 참고.

## 시작하기

```bash
npm install
cp .env .env.local   # VITE_KAKAO_JS_KEY를 .env.local에 채운다 (gitignore됨, 비워둬도 동작)
npm run dev           # http://localhost:5173
```

그 밖의 스크립트:

```bash
npm run build        # tsc -b + vite build (CI가 도는 것)
npm test             # vitest (jsdom, 브라우저 바이너리 불필요)
npm run test:watch   # 감시 모드
npm run lint         # oxlint — tsc가 못 잡는 훅 규칙·닿지 않는 코드
```

백엔드는 `local` 프로파일로 별도 기동해야 한다 (`../src`, `./gradlew bootRun`). 백엔드가 CORS로
`http://localhost:5173`을 허용해두었다(`application-local.yml`의 `cors.allowed-origins`).

## OAuth 리다이렉트 URI 맞추기

카카오/구글 로그인은 **프론트가 백엔드의 `/api/v1/auth/{provider}/authorize`로 이동 → 백엔드가
동의 화면으로 302 → 콜백 페이지가 `code`를 받아 백엔드로 전달**하는 구조다.

인가 URL(`client_id`·`redirect_uri`)은 **백엔드가 조립한다.** 프론트가 만들면 `client_id`가
번들에 인라인되어 공개되는데, 카카오는 그 값(REST API 키)이 로컬 API 자격증명이기도 해서
누구나 꺼내 검색 할당량을 소진시킬 수 있다.

그래서 프론트에는 OAuth 관련 `VITE_` 변수가 없다. 맞춰야 할 곳은 두 군데뿐이다:

1. 백엔드 `KAKAO_REDIRECT_URI` / `GOOGLE_REDIRECT_URI` env
2. 카카오/구글 개발자 콘솔에 등록된 Redirect URI

로컬 기본값은 `http://localhost:5173/oauth/kakao/callback`, `http://localhost:5173/oauth/google/callback`.

## 지도 (카카오맵)

식당 목록(`/restaurants`)과 픽 결과(`/pick`)에 카카오맵을 띄운다. 구현은 `src/maps/`.

### 이 키는 왜 프론트에 있나 (헷갈리기 쉬운 부분)

카카오 앱에는 성격이 다른 키가 여러 개 있고, **이 둘은 다른 값이다.**

| 키 | 두는 곳 | 이유 |
| --- | --- | --- |
| **JavaScript 키** (`VITE_KAKAO_JS_KEY`) | 프론트 번들 | 브라우저가 카카오에 직접 붙는 SDK라 숨길 방법이 없다. 카카오도 공개를 전제로 준다. 보호는 콘솔의 **사이트 도메인 화이트리스트**가 한다. |
| **REST API 키** (`KAKAO_REST_API_KEY`) | 서버 전용 | 로컬 API(장소 검색) 자격증명을 겸한다. 노출되면 누구나 우리 검색 할당량을 소진시킬 수 있고, 도메인으로 막을 수도 없다. 그래서 백엔드가 프록시한다(`/api/v1/kakao/search/keyword`). |

앞서 REST API 키를 번들에서 걷어낸 작업이 있었는데, 그건 위 오른쪽 칸의 이유 때문이지
"카카오 키는 프론트에 두면 안 된다"는 규칙이 있어서가 아니다. 그렇게 오해해서 JS 키까지
서버로 옮기면 지도가 아예 뜨지 않는다.

### 카카오 콘솔에서 할 일

1. [카카오 디벨로퍼스](https://developers.kakao.com) > 내 애플리케이션 > (기존 메뉴픽 앱 선택)
2. **앱 키** 탭에서 **JavaScript 키**를 복사한다. (REST API 키가 아니다)
3. **플랫폼 > Web > 사이트 도메인**에 지도를 띄울 주소를 **전부** 등록한다.
   등록되지 않은 주소에서 부르면 카카오가 SDK 로드를 거부한다.
   - 로컬: `http://localhost:5173`
   - 운영: `http://146.56.116.44` (도메인을 붙이면 그 주소도 추가)
   - 포트가 다르면 다른 도메인으로 취급된다. `http`/`https`도 구분된다.
4. 복사한 키를 넣는다.
   - 로컬: `.env.local`의 `VITE_KAKAO_JS_KEY=`
   - 운영: `.env.prod`의 `VITE_KAKAO_JS_KEY=` (compose가 빌드 인자로 넘겨 번들에 굽는다)

> 카카오맵은 **활성화 설정(ON)이 필요 없다** — 카카오 로그인처럼 별도로 켜는 스위치가 아니라
> 플랫폼 등록만으로 동작한다.

### 키가 없어도 앱은 돌아간다

`VITE_KAKAO_JS_KEY`가 비어 있으면 SDK를 아예 요청하지 않고 지도 자리에 안내 한 줄만 남는다.
목록·검색·저장·픽은 전부 그대로 동작한다. SDK 로드 실패(네트워크 오류, 도메인 미등록으로 인한
거부)도 같은 방식으로 처리한다. 이 계약은 `src/maps/KakaoMap.test.tsx`가 지킨다 —
운영 서버에 키가 아직 없는 상태로 배포될 수 있어서 깨지면 화면이 통째로 죽는다.

## 인증 흐름

- Access Token은 메모리에만 보관한다(브라우저 저장소에 안 씀 — XSS 대비). 새로고침하면 사라지므로,
  앱 부팅 시(`AuthContext`) `/api/v1/auth/refresh`를 한 번 호출해 HttpOnly 쿠키로 조용히 재로그인한다.
- Axios 응답 인터셉터가 401을 만나면 refresh를 한 번 시도하고 원 요청을 재시도한다(`src/api/http.ts`).

## 폴더 구조

```
src/
  a11y/         선택 칩의 aria-pressed, 폼 열릴 때 초점 이동 훅
  api/          axios 클라이언트, 인증 API 함수
  auth/         AuthContext, OAuth authorize URL 빌더, 로그인 후 복귀 경로(returnTo)
  maps/         카카오맵 SDK 로더 + 마커 지도 컴포넌트
  routes/       페이지 컴포넌트 + 라우팅
  test/         vitest 설정과 렌더 헬퍼
```

`a11y/`가 따로 있는 이유: 선택 상태를 클래스와 `aria-pressed`로 따로 쓰면 클래스만 고치고 aria를
빠뜨려도 **화면상 아무 이상이 없어** 발견되지 않는다. 두 값을 한 곳에서 만들어 어긋남을 막는다.

## 구현 현황

[Requirements.md](../docs/Requirements.md)의 유저 스토리 전 화면이 구현됐다: 로그인/세션 복원, 메뉴 관리(US-2), 식당 관리·카카오 장소 검색·메뉴 연결(US-3), 필터 랜덤 픽(US-4), 히스토리·방문 처리(US-5), 설정·회원 탈퇴.

로그인 후에는 막혔던 화면으로 되돌아간다(`auth/returnTo.ts`). 접근성은 화면 제목 `h1` 계층, 선택 칩의
`aria-pressed`, 인라인 폼의 초점 이동까지 처리했다.

남은 것: 실사용 E2E 검증.
