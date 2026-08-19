// 인가 코드를 받아오는 첫 단계 — 백엔드의 리다이렉트 엔드포인트로 보낸다.
//
// 인가 URL 조립(client_id·redirect_uri)은 서버가 한다. 여기서 만들면 client_id가 번들에
// 인라인되어 공개되는데, 카카오는 그 값(REST API 키)이 로컬 API 자격증명이기도 해서
// 누구나 꺼내 검색 할당량을 소진시킬 수 있다. redirect_uri도 서버 설정 하나만 보게 되어
// 프론트·백엔드 값이 어긋나 토큰 교환이 실패하던 사고가 구조적으로 사라진다.

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
 *
 * 서버가 아니라 브라우저가 만드는 이유는 대조하는 주체가 브라우저이기 때문이다. 서버가 새로
 * 만들면 sessionStorage에 짝이 없어 콜백 검증이 성립하지 않는다. 서버는 형식만 확인하고 싣는다.
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

/**
 * 백엔드 리다이렉트 엔드포인트 주소. 브라우저는 이 주소로 이동하고, 서버가 302로 동의 화면에 넘긴다.
 *
 * http 인스턴스가 아니라 window.location으로 이동해야 하므로 baseURL을 직접 붙인다.
 * 운영은 nginx 동일 출처라 VITE_API_BASE_URL이 빈 문자열이고, 그때는 상대 경로가 된다.
 */
function authorizeEndpoint(provider: OAuthProviderKey): string {
  const base = import.meta.env.VITE_API_BASE_URL ?? "";
  const state = issueState(provider);
  return `${base}/api/v1/auth/${provider}/authorize?state=${encodeURIComponent(state)}`;
}

export function kakaoAuthorizeUrl(): string {
  return authorizeEndpoint("kakao");
}

export function googleAuthorizeUrl(): string {
  return authorizeEndpoint("google");
}
