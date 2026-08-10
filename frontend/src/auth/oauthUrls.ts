// 인가 코드를 받아오는 첫 단계 — Kakao/Google의 authorize 엔드포인트로 리다이렉트한다.
// 여기서 쓰는 redirect_uri는 반드시 백엔드 OAUTH_*_REDIRECT_URI(Planning.md 6.2)와 동일해야
// 토큰 교환이 성공한다 (KakaoOAuthProvider.getUserProfile 참고).

export type OAuthProviderKey = "kakao" | "google";

const STATE_KEY_PREFIX = "oauth_state_";

function stateStorageKey(provider: OAuthProviderKey): string {
  return `${STATE_KEY_PREFIX}${provider}`;
}

/**
 * CSRF 방어용 state를 만들어 sessionStorage에 보관한다.
 *
 * 공격자가 자기 인가 코드를 피해자 브라우저의 콜백 URL로 밀어넣으면 피해자가 공격자 계정으로
 * 로그인된 채 데이터를 쌓게 된다(로그인 CSRF). 콜백에서 이 값과 대조하면, 이 브라우저가
 * 실제로 시작한 로그인만 통과한다. sessionStorage는 탭 단위라 리다이렉트 왕복 동안만 남는다.
 */
function issueState(provider: OAuthProviderKey): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  const state = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  sessionStorage.setItem(stateStorageKey(provider), state);
  return state;
}

/** 콜백에서 받은 state를 저장값과 대조한다. 재사용을 막기 위해 결과와 무관하게 저장값은 지운다. */
export function consumeState(provider: OAuthProviderKey, received: string | null): boolean {
  const expected = sessionStorage.getItem(stateStorageKey(provider));
  sessionStorage.removeItem(stateStorageKey(provider));
  return !!expected && !!received && expected === received;
}

export function kakaoAuthorizeUrl(): string {
  const params = new URLSearchParams({
    client_id: import.meta.env.VITE_KAKAO_CLIENT_ID,
    redirect_uri: import.meta.env.VITE_KAKAO_REDIRECT_URI,
    response_type: "code",
    state: issueState("kakao"),
  });
  return `https://kauth.kakao.com/oauth/authorize?${params.toString()}`;
}

export function googleAuthorizeUrl(): string {
  const params = new URLSearchParams({
    client_id: import.meta.env.VITE_GOOGLE_CLIENT_ID,
    redirect_uri: import.meta.env.VITE_GOOGLE_REDIRECT_URI,
    response_type: "code",
    scope: "openid email profile",
    state: issueState("google"),
  });
  return `https://accounts.google.com/o/oauth2/v2/auth?${params.toString()}`;
}
