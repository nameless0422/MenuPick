import { http, type ApiResponse } from "./http";

export type Provider = "kakao" | "google";

/** 화면에 그릴 수 있는 제공자 전부. 설정 화면의 연동 목록이 이 순서로 나온다. */
export const PROVIDERS: readonly Provider[] = ["kakao", "google"];

/** 버튼·안내 문구에서 쓰는 이름. 서버가 주는 값(KAKAO)을 그대로 보여주지 않기 위한 것. */
export const PROVIDER_LABELS: Record<Provider, string> = {
  kakao: "카카오",
  google: "구글",
};

/** 백엔드 AuthRequest.PASSWORD_MIN_LENGTH와 같은 값 — 어긋나면 서버에서만 걸려 폼이 헛돈다. */
export const PASSWORD_MIN_LENGTH = 8;

/**
 * 백엔드 AuthRequest.PASSWORD_MAX_LENGTH와 같은 값.
 *
 * <p>상한이 있는 이유는 길이 제한 자체보다 Argon2 비용이다 — 입력이 길수록 해싱이 비싸진다.
 * 프론트에서 막지 않으면 사용자는 "저장"을 눌러야 400을 받는다.
 */
export const PASSWORD_MAX_LENGTH = 128;

export interface Me {
  email: string | null;
  nickname: string;
  /** 자체 계정 비밀번호가 있는지. 소셜 전용 계정에는 비밀번호 변경 UI를 띄우지 않는다. */
  hasPassword: boolean;
  /** 연동된 소셜 제공자. LOCAL은 들어오지 않는다 — 그건 hasPassword가 알려준다. */
  linkedProviders: Provider[];
}

/**
 * 서버는 DB에 저장된 이름(KAKAO, GOOGLE)을 주는데, 화면은 라우트·엔드포인트와 같은
 * 소문자 키로 제공자를 다룬다. 경계에서 한 번만 맞춰 둔다 — 화면마다 toLowerCase를
 * 흩뿌리면 한 군데만 빠뜨려도 "연동됨"이 조용히 "연동 안 됨"으로 보인다.
 *
 * 아직 이 프론트가 모르는 제공자는 버린다. 버튼도 라벨도 없어 그릴 수 없고, 목록에 남겨두면
 * 렌더링이 undefined 라벨로 깨진다. 서버가 먼저 배포돼 새 제공자를 내려보내는 상황이다.
 */
function toProviders(raw: string[] | undefined): Provider[] {
  return (raw ?? [])
    .map((name) => name.toLowerCase())
    .filter((name): name is Provider => (PROVIDERS as readonly string[]).includes(name));
}

export async function loginWithOAuth(provider: Provider, code: string) {
  const res = await http.post<ApiResponse<{ accessToken: string }>>(`/api/v1/auth/${provider}`, {
    code,
  });
  return res.data.data!.accessToken;
}

// ---- 소셜 계정 연동 ----

/**
 * 연동·해제 결과.
 *
 * accessToken이 함께 오는 이유: 서버는 로그인 수단이 바뀌면 그 계정의 모든 세션을 끊는다
 * (비밀번호 변경과 같은 처리). 방금 변경한 당사자의 세션도 함께 끊기므로, 이 토큰을 받아
 * 적용하지 않으면 사용자는 연동 버튼을 한 번 눌렀다가 그대로 로그아웃당한다.
 */
export interface LinkResult {
  linkedProviders: Provider[];
  accessToken: string;
}

/**
 * 로그인한 계정에 소셜 계정을 붙인다. 인가 코드는 로그인과 같은 방식으로 받아온다
 * (auth/oauthUrls.ts의 linkAuthorizeUrl).
 *
 * 응답은 연동 후의 전체 목록이라 /me를 한 번 더 부르지 않고 캐시를 맞출 수 있다.
 */
export async function linkSocialAccount(provider: Provider, code: string): Promise<LinkResult> {
  const res = await http.post<ApiResponse<{ linkedProviders: string[]; accessToken: string }>>(
    `/api/v1/auth/${provider}/link`,
    { code },
  );
  const data = res.data.data!;
  return { linkedProviders: toProviders(data.linkedProviders), accessToken: data.accessToken };
}

/** 연동 해제. 남는 로그인 수단이 없어지는 경우는 서버가 LAST_LOGIN_METHOD로 막는다. */
export async function unlinkSocialAccount(provider: Provider): Promise<LinkResult> {
  const res = await http.delete<ApiResponse<{ linkedProviders: string[]; accessToken: string }>>(
    `/api/v1/auth/${provider}/link`,
  );
  const data = res.data.data!;
  return { linkedProviders: toProviders(data.linkedProviders), accessToken: data.accessToken };
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

export async function fetchMe(): Promise<Me> {
  const res = await http.get<ApiResponse<Omit<Me, "linkedProviders"> & { linkedProviders: string[] }>>(
    "/api/v1/auth/me",
  );
  const me = res.data.data!;
  return { ...me, linkedProviders: toProviders(me.linkedProviders) };
}

// ---- 세션 ----

export async function logout() {
  await http.delete<ApiResponse<null>>("/api/v1/auth/logout");
}

export async function withdraw() {
  await http.delete<ApiResponse<null>>("/api/v1/auth/withdraw");
}
