import axios, { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from "axios";

export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  message?: string;
  errorCode?: string;
  errors?: { field: string; message: string }[];
}

// Access Token은 메모리에만 둔다 — localStorage/sessionStorage는 XSS로 읽힐 수 있어 쓰지 않는다.
// (백엔드 docs/DecisionLog.md D-002와 짝을 이루는 프론트 쪽 결정: Refresh Token은 HttpOnly 쿠키,
//  Access Token은 메모리. 새로고침하면 accessToken이 날아가므로 앱 부팅 시 항상 /auth/refresh를
//  한 번 호출해 재발급받는다 — AuthContext에서 처리)
let accessToken: string | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

// axios의 timeout 기본값은 0 = 무한 대기다. 백엔드가 TCP 연결은 받으면서 응답을 주지 못하는
// 상태(톰캣 스레드 50개 고갈, Hikari 커넥션 풀 10개 대기 등 — application-prod.yml)에서는
// 요청이 영원히 pending으로 남는다. 그러면 AuthProvider가 부팅 때 부르는 refresh가 끝나지 않아
// ProtectedRoute의 isLoading이 계속 true고, 앱 전체가 "로딩 중..."에 고정된다.
// 에러도 재시도 버튼도 없이 새로고침 말고는 빠져나갈 길이 없는 상태가 된다.
//
// 15초 근거 — "정상 응답의 상한"보다는 넉넉하되 사용자가 포기하기 전에 끊는 값:
//   * Hikari connection-timeout 5s — DB 커넥션 대기가 이 값에서 잘린다
//   * 카카오/네이버 프록시 연결 2s + 응답 3s (KakaoLocalClient·NaverMapsClient)
//   즉 가장 느린 정상 경로도 10s 안에는 성공이든 실패든 응답이 돌아온다.
// (예외: 메일 큐가 포화되면 MailAsyncConfig의 CallerRunsPolicy가 SMTP 발송을 요청 스레드로
//  되돌려 가입 요청이 이보다 길어질 수 있다. 다만 그건 이미 정상 상태가 아니고, 그때도
//  가입 자체는 커밋된 뒤라 화면이 무한정 매달리는 것보다 타임아웃으로 끊는 편이 낫다.)
const REQUEST_TIMEOUT_MS = 15_000;

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  // Refresh Token 쿠키(HttpOnly, SameSite=Strict)를 요청에 실어 보내려면 필수.
  withCredentials: true,
  timeout: REQUEST_TIMEOUT_MS,
});

/**
 * 인터셉터가 요청에 붙여 두는 표식.
 *
 * - `_retried`: 이 요청이 이미 401 재시도를 한 번 썼는지. 무한 루프 방지.
 * - `_sentWithToken`: 이 요청이 어떤 Access Token으로 나갔는지. 401을 받았을 때
 *   "만료된 토큰 때문인가, 아니면 이미 갈린 지난 토큰 때문인가"를 가르는 유일한 단서다.
 */
interface TrackedRequestConfig extends InternalAxiosRequestConfig {
  _retried?: boolean;
  _sentWithToken?: string | null;
}

http.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  // 재시도로 다시 들어올 때도 이 인터셉터를 거치므로 값은 항상 "직전에 실제로 실린 토큰"이다.
  (config as TrackedRequestConfig)._sentWithToken = accessToken;
  return config;
});

// refresh가 최종 실패하면(= 세션이 끝났으면) 알려야 할 대상. AuthProvider가 등록해
// 인증 상태와 캐시를 정리한다. 이게 없으면 화면은 계속 로그인 상태로 남아, 사용자가
// 로그인 화면으로 나가지도 못하고 실패만 반복하는 상태에 갇힌다.
let sessionExpiredHandler: (() => void) | null = null;

export function setSessionExpiredHandler(handler: (() => void) | null) {
  sessionExpiredHandler = handler;
}

/**
 * 공통 응답 봉투에서 본문을 꺼낸다.
 *
 * 여태 호출부는 전부 {@code res.data.data!}로 단언했다. 타입은 {@code data?: T}인데 단언이
 * 그 물음표를 지워버려, 서버가 data 없이 200을 주면 undefined가 화면까지 그대로 흘러가
 * 렌더 중에 "Cannot read properties of undefined"로 터진다 — 즉 실패가 API 경계가 아니라
 * 컴포넌트 트리 한복판에서 드러나고, 사용자가 보는 건 ErrorBoundary의 전면 오류 화면이다.
 * 여기서 끊으면 실패가 나머지 API 에러와 같은 자리(catch)에서 같은 한국어 문구로 처리된다.
 *
 * {@code null}을 정상 본문으로 쓰는 응답(void 계열)은 이 함수를 거치지 않는다 —
 * 그런 호출부는 애초에 반환값을 쓰지 않는다.
 */
export function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  const data = res.data?.data;
  if (data === undefined || data === null) {
    throw new Error(EMPTY_RESPONSE_MESSAGE);
  }
  return data;
}

// 401을 만나면 /auth/refresh로 한 번 갱신을 시도하고, 성공하면 원래 요청을 재시도한다.
// refresh 자체가 실패하면(쿠키 만료 등) 더 이상 재시도하지 않고 그대로 에러를 던진다 — 무한 루프 방지.
let refreshPromise: Promise<string> | null = null;

async function refreshAccessToken(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = http
      .post<ApiResponse<{ accessToken: string }>>("/api/v1/auth/refresh")
      .then((res) => {
        const token = unwrap(res).accessToken;
        setAccessToken(token);
        return token;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

// 401을 만나도 재발급을 시도하면 안 되는 "인증 전" 경로들.
//
// 이 경로의 401은 세션 만료가 아니라 자격 증명 자체의 실패다
// (백엔드 ErrorCode: INVALID_CREDENTIALS 401, OAUTH_INVALID_CODE 401).
// refresh로는 절대 고쳐지지 않는데도 부르면, 비밀번호 오타 1회가 인증 버킷을 2회 소모한다 —
// RateLimitFilter의 인증 버킷은 IP 기준 분당 10회이고 그 목록에 /auth/refresh도 들어 있다.
// 여기에 앱 부팅 시 refresh 1회가 더해지므로, 오타를 네댓 번 낸 사용자가 429에 걸리고
// 그때부터 1분간 "올바른 비밀번호로도" 로그인하지 못한다. 같은 IP 버킷을 공유하는
// resend-verification·password-reset까지 함께 막히고, 공유 NAT(사무실·카페)에서는 더 빨리 터진다.
//
// 이 목록은 백엔드 두 곳과 반드시 함께 움직여야 한다 — 어긋나면 조용히 같은 증상으로 되돌아간다:
//   * SecurityConfig.securityFilterChain()의 permitAll 목록 (= 토큰 없이 호출하는 경로)
//   * RateLimitFilter.AUTH_RATE_LIMITED_PATHS (= 인증 버킷을 소모하는 경로)
// 반대로 /auth/me·/auth/password·/auth/withdraw는 인증이 필요한 경로라 401이 곧
// "Access Token 만료"를 뜻한다. 여기 넣으면 재발급 경로가 사라져 사용자가 30분(AT 수명)마다
// 로그인 화면으로 튕긴다. 이름이 닮은 /auth/password-reset과 헷갈리지 말 것.
const PRE_AUTH_PATHS = [
  "/api/v1/auth/kakao",
  "/api/v1/auth/google",
  "/api/v1/auth/refresh",
  "/api/v1/auth/signup",
  "/api/v1/auth/login",
  "/api/v1/auth/verify-email",
  "/api/v1/auth/resend-verification",
  "/api/v1/auth/password-reset",
  "/api/v1/auth/password-reset/confirm",
  // 로그아웃은 AT가 만료된 뒤에야 가장 필요하다. 그래서 permitAll이 됐고, 토큰이 없어도
  // 서버가 쿠키에서 주체를 찾아 세션을 지운다. 여기서 재발급을 시도할 이유가 없다.
  "/api/v1/auth/logout",
  // permitAll이라 401 자체가 나지 않지만, "토큰 없이 부르는 경로"라는 같은 이유로 여기 둔다.
  "/api/v1/pick/demo",
];

function isPreAuthCall(url: string | undefined): boolean {
  if (!url) return false;
  // baseURL이 붙은 절대 URL로 들어올 수 있어 경로 끝으로 비교한다. 쿼리스트링은 떼어낸다 —
  // includes()로 대충 맞추면 나중에 "/api/v1/auth/login-history" 같은 경로가 생겼을 때
  // 조용히 함께 걸려든다.
  const path = url.split("?")[0].replace(/\/+$/, "");
  return PRE_AUTH_PATHS.some((preAuthPath) => path.endsWith(preAuthPath));
}

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as TrackedRequestConfig | undefined;
    const isPreAuth = isPreAuthCall(original?.url);

    if (error.response?.status === 401 && original && !original._retried && !isPreAuth) {
      original._retried = true;

      // 이 요청이 나간 뒤에 토큰이 이미 갈렸다면, 이 401은 지나간 토큰이 받아온 것이다.
      // 재발급은 방금 끝났으니 다시 부를 이유가 없고, 새 토큰으로 한 번 더 보내면 된다.
      //
      // 이 가지가 없으면 401이 조금씩 어긋나 도착할 때마다 refresh가 새로 나간다.
      // refreshPromise는 .finally에서 즉시 비워지므로 "완전히 겹친" 401만 하나로 합쳐지고,
      // 수십~수백 ms 간격으로 흩어져 오는 실패들은 각자 refresh를 부른다. /auth/refresh는
      // 인증 버킷(IP당 분당 10회)에 있어, 화면 하나가 병렬로 부르는 서너 개 쿼리가 동시에
      // 만료를 만나는 것만으로 그 버킷을 다 태운다 — 공유 NAT(사무실·카페)에서는 로그인·
      // 가입·비밀번호 재설정까지 함께 429로 막힌다.
      const currentToken = getAccessToken();
      if (currentToken && currentToken !== original._sentWithToken) {
        original.headers.Authorization = `Bearer ${currentToken}`;
        return http(original);
      }

      try {
        const token = await refreshAccessToken();
        original.headers.Authorization = `Bearer ${token}`;
        return http(original);
      } catch {
        setAccessToken(null);
        sessionExpiredHandler?.();
        // refresh 에러가 아니라 원래 요청의 에러를 돌려준다 — 호출부가 기대하는 건
        // 자기가 보낸 요청의 실패다(errorCode 분기 등). 세션 종료는 위 통지로 처리된다.
        return Promise.reject(error);
      }
    }
    return Promise.reject(error);
  },
);

export { refreshAccessToken };

// axios가 만드는 error.message는 전부 영어다("Network Error", "timeout of 15000ms exceeded",
// "Request failed with status code 502"). 그대로 내보내면 한국어 화면 한복판에 영어 한 줄이 뜨고,
// 사용자는 자기 잘못인지 서버 문제인지, 다시 시도하면 되는지조차 알 수 없다.
// 백엔드 공통 응답(message)이 있으면 그걸 쓰고, 없을 때만 아래 문구로 갈아 끼운다.
const NETWORK_ERROR_MESSAGE = "서버에 연결할 수 없습니다. 네트워크 상태를 확인한 뒤 다시 시도해 주세요.";
const TIMEOUT_ERROR_MESSAGE = "서버 응답이 너무 늦습니다. 잠시 후 다시 시도해 주세요.";
const SERVER_ERROR_MESSAGE = "서버에 문제가 생겼습니다. 잠시 후 다시 시도해 주세요.";
// unwrap이 던지는 문구. 사용자가 할 수 있는 일이 "잠시 후 재시도"뿐이라는 점에서 5xx와 같다.
const EMPTY_RESPONSE_MESSAGE = "서버 응답이 비어 있습니다. 잠시 후 다시 시도해 주세요.";

// axios 에러에서 백엔드 공통 응답의 message를 꺼낸다. 화면에서 에러 표시할 때 공용으로 사용.
export function apiErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data as ApiResponse<unknown> | undefined;
    if (body?.message) return body.message;

    // 응답이 아예 없는 경우 = 서버까지 닿지 못했거나 기다리다 끊은 것. 둘은 사용자가 할 일이
    // 달라서(연결 확인 vs 잠시 후 재시도) 같은 문구로 뭉뚱그리지 않는다.
    if (!error.response) {
      const timedOut = error.code === AxiosError.ECONNABORTED || error.code === AxiosError.ETIMEDOUT;
      return timedOut ? TIMEOUT_ERROR_MESSAGE : NETWORK_ERROR_MESSAGE;
    }

    // 여기까지 왔다면 응답은 있는데 공통 응답 형식이 아니다 — nginx의 502 HTML처럼
    // 백엔드 예외 핸들러를 거치지 않은 응답이 대표적이다.
    return error.response.status >= 500 ? SERVER_ERROR_MESSAGE : error.message;
  }
  return error instanceof Error ? error.message : String(error);
}

// 백엔드 에러 코드 확인용 (예: NO_PICK_CANDIDATES 분기 처리)
export function apiErrorCode(error: unknown): string | undefined {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data as ApiResponse<unknown> | undefined;
    return body?.errorCode;
  }
  return undefined;
}
