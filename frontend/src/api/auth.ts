import { http, type ApiResponse } from "./http";

export type Provider = "kakao" | "google";

/** 백엔드 AuthRequest.PASSWORD_MIN_LENGTH와 같은 값 — 어긋나면 서버에서만 걸려 폼이 헛돈다. */
export const PASSWORD_MIN_LENGTH = 8;

export interface Me {
  email: string | null;
  nickname: string;
  /** 자체 계정 비밀번호가 있는지. 소셜 전용 계정에는 비밀번호 변경 UI를 띄우지 않는다. */
  hasPassword: boolean;
}

export async function loginWithOAuth(provider: Provider, code: string) {
  const res = await http.post<ApiResponse<{ accessToken: string }>>(`/api/v1/auth/${provider}`, {
    code,
  });
  return res.data.data!.accessToken;
}

// ---- 자체 계정 ----

export async function signup(email: string, password: string, nickname: string) {
  await http.post<ApiResponse<null>>("/api/v1/auth/signup", { email, password, nickname });
}

export async function login(email: string, password: string) {
  const res = await http.post<ApiResponse<{ accessToken: string }>>("/api/v1/auth/login", {
    email,
    password,
  });
  return res.data.data!.accessToken;
}

/** 메일 링크의 토큰으로 인증을 마치고 곧바로 로그인한다. */
export async function verifyEmail(token: string) {
  const res = await http.post<ApiResponse<{ accessToken: string }>>("/api/v1/auth/verify-email", {
    token,
  });
  return res.data.data!.accessToken;
}

export async function resendVerification(email: string) {
  await http.post<ApiResponse<null>>("/api/v1/auth/resend-verification", { email });
}

export async function requestPasswordReset(email: string) {
  await http.post<ApiResponse<null>>("/api/v1/auth/password-reset", { email });
}

export async function confirmPasswordReset(token: string, newPassword: string) {
  const res = await http.post<ApiResponse<{ accessToken: string }>>(
    "/api/v1/auth/password-reset/confirm",
    { token, newPassword },
  );
  return res.data.data!.accessToken;
}

export async function changePassword(currentPassword: string, newPassword: string) {
  const res = await http.patch<ApiResponse<{ accessToken: string }>>("/api/v1/auth/password", {
    currentPassword,
    newPassword,
  });
  return res.data.data!.accessToken;
}

export async function fetchMe() {
  const res = await http.get<ApiResponse<Me>>("/api/v1/auth/me");
  return res.data.data!;
}

// ---- 세션 ----

export async function logout() {
  await http.delete<ApiResponse<null>>("/api/v1/auth/logout");
}

export async function withdraw() {
  await http.delete<ApiResponse<null>>("/api/v1/auth/withdraw");
}
