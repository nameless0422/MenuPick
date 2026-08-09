import axios, { AxiosError, type InternalAxiosRequestConfig } from "axios";

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

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  // Refresh Token 쿠키(HttpOnly, SameSite=Strict)를 요청에 실어 보내려면 필수.
  withCredentials: true,
});

http.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

// 401을 만나면 /auth/refresh로 한 번 갱신을 시도하고, 성공하면 원래 요청을 재시도한다.
// refresh 자체가 실패하면(쿠키 만료 등) 더 이상 재시도하지 않고 그대로 에러를 던진다 — 무한 루프 방지.
let refreshPromise: Promise<string> | null = null;

async function refreshAccessToken(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = http
      .post<ApiResponse<{ accessToken: string }>>("/api/v1/auth/refresh")
      .then((res) => {
        const token = res.data.data!.accessToken;
        setAccessToken(token);
        return token;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined;
    const isRefreshCall = original?.url?.includes("/auth/refresh");

    if (error.response?.status === 401 && original && !original._retried && !isRefreshCall) {
      original._retried = true;
      try {
        const token = await refreshAccessToken();
        original.headers.Authorization = `Bearer ${token}`;
        return http(original);
      } catch (refreshError) {
        setAccessToken(null);
        return Promise.reject(refreshError);
      }
    }
    return Promise.reject(error);
  },
);

export { refreshAccessToken };
