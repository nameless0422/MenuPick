// Access Token을 k6 안에서 직접 만든다.
//
// 왜 이게 되는가: JwtAuthenticationFilter는 서명·만료·token_type만 확인하고
// 요청마다 DB나 Redis를 조회하지 않는다(JwtAuthenticationFilter.java:33-40,
// JwtTokenProvider.java:96-112). 토큰 내용도 sub=userId가 전부다. 즉 서명 시크릿과
// 사용자 id만 있으면 유효한 토큰을 오프라인으로 만들 수 있다.
//
// 얻는 것 셋:
//   1. OAuth 왕복·메일 인증이 필요 없다 → 부하 테스트가 완전히 자동 실행된다
//   2. 인증 레이트 리밋(IP 기준 10/분)을 아예 건드리지 않는다
//   3. 측정에 Argon2 검증 비용(9MiB·수십 ms)이 섞이지 않는다
//
// 왜 스크립트 "밖에서" 한 번 만들어 넘기면 안 되는가: 운영과 같은 30분 만료를 쓰면
// soak(1시간 이상) 도중에 만료돼 그 뒤 요청이 전부 401이 된다. 재발급이 필요하고,
// 재발급을 하려면 서명을 스크립트 안에서 할 수 있어야 한다.
//
// ⚠ 이 방식은 부하 생성기에 서명 시크릿을 넘긴다는 뜻이다. 운영 시크릿을 이렇게 쓰지 말 것.
//    부하 테스트 전용 환경의 전용 JWT_SECRET으로만 돌린다 (docs/LoadTestPlan.md 4절).

import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

/** 운영과 같은 수명(application-prod.yml의 jwt.access-token-expiry = 1800000ms). */
const TOKEN_LIFETIME_SEC = 30 * 60;

/**
 * 만료 이 시간 전부터 새로 발급한다.
 *
 * 만료 직전에 발급된 토큰이 요청 왕복 도중 만료돼 401이 나는 것을 막는다. 그 401은
 * 서버 문제가 아닌데도 에러율에 잡혀, 없는 장애를 찾게 만든다.
 */
const RENEW_BEFORE_SEC = 5 * 60;

/** VU별 토큰 캐시. 매 요청마다 HMAC을 다시 계산하면 부하 생성기 쪽 CPU가 측정에 섞인다. */
const cache = {};

function b64url(value) {
  return encoding.b64encode(value, 'rawurl');
}

function nowSec() {
  return Math.floor(Date.now() / 1000);
}

/**
 * userId에 대한 HS256 Access Token을 만든다.
 *
 * jjwt는 subject를 문자열로 읽고 JwtTokenProvider.getUserId가 Long.parseLong으로
 * 되돌리므로(JwtTokenProvider.java:73-83), sub는 반드시 숫자 문자열이어야 한다.
 */
function mint(userId, secret) {
  const issuedAt = nowSec();
  const expiresAt = issuedAt + TOKEN_LIFETIME_SEC;

  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(
    JSON.stringify({
      sub: String(userId),
      // 이 클레임이 없거나 "refresh"면 parseAccessToken이 거부한다 (JwtTokenProvider.java:36-38).
      token_type: 'access',
      iat: issuedAt,
      exp: expiresAt,
    }),
  );

  const signingInput = `${header}.${payload}`;
  // base64rawurl = 패딩 없는 base64url. JWT 규격이 요구하는 형식이라 후처리가 필요 없다.
  const signature = crypto.hmac('sha256', secret, signingInput, 'base64rawurl');

  return { token: `${signingInput}.${signature}`, expiresAt };
}

/**
 * 캐시된 토큰을 주되, 만료가 가까우면 새로 발급한다.
 *
 * @param {number|string} userId 시드된 사용자 id
 * @param {string} secret 대상 환경의 jwt.secret
 */
export function accessTokenFor(userId, secret) {
  const cached = cache[userId];
  if (cached && cached.expiresAt - nowSec() > RENEW_BEFORE_SEC) {
    return cached.token;
  }

  const fresh = mint(userId, secret);
  cache[userId] = fresh;
  return fresh.token;
}

/** 인증 헤더까지 붙인 요청 파라미터. 호출부에서 tags만 얹어 쓴다. */
export function authParams(userId, secret, tags) {
  return {
    headers: {
      Authorization: `Bearer ${accessTokenFor(userId, secret)}`,
      'Content-Type': 'application/json',
    },
    tags: tags || {},
  };
}
