import { http, type ApiResponse } from "./http";

export type Provider = "kakao" | "google";

export async function loginWithOAuth(provider: Provider, code: string) {
  const res = await http.post<ApiResponse<{ accessToken: string }>>(`/api/v1/auth/${provider}`, {
    code,
  });
  return res.data.data!.accessToken;
}

export async function logout() {
  await http.delete<ApiResponse<null>>("/api/v1/auth/logout");
}

export async function withdraw() {
  await http.delete<ApiResponse<null>>("/api/v1/auth/withdraw");
}
