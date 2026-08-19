// 인가 코드를 받아오는 첫 단계 — 백엔드의 리다이렉트 엔드포인트로 보낸다.
//
// 인가 URL 조립(client_id·redirect_uri)은 서버가 한다. 여기서 만들면 client_id가 번들에
// 인라인되어 공개되는데, 카카오는 그 값(REST API 키)이 로컬 API 자격증명이기도 해서
// 누구나 꺼내 검색 할당량을 소진시킬 수 있다. redirect_uri도 서버 설정 하나만 보게 되어
// 프론트·백엔드 값이 어긋나 토큰 교환이 실패하던 사고가 구조적으로 사라진다.

export type OAuthProviderKey = "kakao" | "google";

/**
 * 이 리다이렉트를 무엇 하러 시작했는지.
 *
 * - `login`: 소셜로 로그인. 이미 연동된 계정이 있어야만 통과한다 — 소셜만으로는 가입되지 않는다.
 * - `link`: 이미 로그인한 계정에 소셜 계정을 붙인다.
 *
 * 콜백 주소는 둘이 같다. 제공자 콘솔에 등록한 redirect_uri가 하나뿐이라 화면을 갈라놓을 수
 * 없기 때문이다. 그래서 "무엇을 하러 갔는지"를 브라우저가 기억하고 있어야 한다.
 */
export type OAuthMode = "login" | "link";

const REQUEST_KEY_PREFIX = "oauth_request_";

interface PendingRequest {
  state: string;
  mode: OAuthMode;
}

function requestStorageKey(provider: OAuthProviderKey): string {
  return `${REQUEST_KEY_PREFIX}${provider}`;
}

/**
 * CSRF 방어용 state를 만들고, 모드와 함께 sessionStorage에 보관한다.
 *
 * 공격자가 자기 인가 코드를 피해자 브라우저의 콜백 URL로 밀어넣으면 피해자가 공격자 계정으로
 * 로그인된 채 데이터를 쌓게 된다(로그인 CSRF). 콜백에서 이 값과 대조하면, 이 브라우저가
 * 실제로 시작한 로그인만 통과한다. sessionStorage는 탭 단위라 리다이렉트 왕복 동안만 남는다.
 *
 * 서버가 아니라 브라우저가 state를 만드는 이유는 대조하는 주체가 브라우저이기 때문이다.
 * 서버가 새로 만들면 sessionStorage에 짝이 없어 콜백 검증이 성립하지 않는다.
 * 서버는 형식([A-Za-z0-9_-]{16,128})만 확인하고 그대로 싣는다.
 *
 * 모드를 state 문자열 안에 섞지 않는 이유:
 *   * 서버의 형식 검증을 통과시키려면 접두사까지 그 문자 집합에 맞춰야 해, CSRF 토큰의
 *     형식이 앱의 의미와 얽힌다 — 나중에 모드가 하나 늘 때 서버 정규식을 다시 봐야 한다.
 *   * state는 제공자에게 그대로 전달되어 그쪽 로그에 남는다. 굳이 우리 앱의 내부 동작을
 *     실어 보낼 이유가 없다.
 * 모드와 state를 한 항목에 함께 넣는 이유는 둘이 같은 시점에 생겨 같은 시점에 소비되기
 * 때문이다. 키를 나누면 한쪽만 남아 "state는 맞는데 모드를 모르는" 상태가 생길 수 있다.
 */
function issueRequest(provider: OAuthProviderKey, mode: OAuthMode): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  const state = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  const pending: PendingRequest = { state, mode };
  sessionStorage.setItem(requestStorageKey(provider), JSON.stringify(pending));
  return state;
}

/**
 * 콜백에서 받은 state를 저장값과 대조하고, 통과하면 시작할 때의 모드를 돌려준다.
 * 어긋나면 null — 호출부는 인가 코드를 서버로 보내지 말아야 한다.
 *
 * 재사용을 막기 위해 결과와 무관하게 저장값은 지운다.
 */
export function consumeOAuthRequest(
  provider: OAuthProviderKey,
  received: string | null,
): OAuthMode | null {
  const raw = sessionStorage.getItem(requestStorageKey(provider));
  sessionStorage.removeItem(requestStorageKey(provider));

  if (!raw || !received) return null;

  // 같은 오리진의 아무 스크립트나 sessionStorage에 쓸 수 있고, 예전 버전이 남긴 형식이
  // 들어 있을 수도 있다. 깨진 값에 예외로 죽지 말고 "검증 실패"로 떨어뜨린다.
  let pending: PendingRequest;
  try {
    pending = JSON.parse(raw) as PendingRequest;
  } catch {
    return null;
  }

  if (pending?.state !== received) return null;
  return pending.mode === "link" ? "link" : "login";
}

/**
 * 백엔드 리다이렉트 엔드포인트 주소. 브라우저는 이 주소로 이동하고, 서버가 302로 동의 화면에 넘긴다.
 *
 * http 인스턴스가 아니라 window.location으로 이동해야 하므로 baseURL을 직접 붙인다.
 * 운영은 nginx 동일 출처라 VITE_API_BASE_URL이 빈 문자열이고, 그때는 상대 경로가 된다.
 *
 * 연동도 로그인과 같은 authorize 엔드포인트를 탄다 — 인가 코드를 받아오는 절차 자체는
 * 똑같고, 그 코드를 로그인에 쓸지 연동에 쓸지는 콜백에서 갈린다.
 */
function authorizeEndpoint(provider: OAuthProviderKey, mode: OAuthMode): string {
  const base = import.meta.env.VITE_API_BASE_URL ?? "";
  const state = issueRequest(provider, mode);
  return `${base}/api/v1/auth/${provider}/authorize?state=${encodeURIComponent(state)}`;
}

/** 소셜로 로그인하러 간다. 연동된 적 없는 계정이면 서버가 거절한다. */
export function loginAuthorizeUrl(provider: OAuthProviderKey): string {
  return authorizeEndpoint(provider, "login");
}

/** 로그인한 계정에 이 제공자를 붙이러 간다. */
export function linkAuthorizeUrl(provider: OAuthProviderKey): string {
  return authorizeEndpoint(provider, "link");
}
