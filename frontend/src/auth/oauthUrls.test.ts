import { beforeEach, describe, expect, it, vi } from "vitest";
import { consumeOAuthRequest, linkAuthorizeUrl, loginAuthorizeUrl } from "./oauthUrls";

/** 인가 주소에 실린 state를 꺼낸다 — 콜백이 받게 될 값과 같다. */
function stateOf(url: string): string | null {
  return new URL(url, "http://localhost").searchParams.get("state");
}

describe("oauthUrls", () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.unstubAllEnvs();
  });

  // 이 파일의 존재 이유 자체다 — client_id가 번들에 들어가면 카카오 REST API 키가 공개된다.
  it("인가 주소에 client_id·redirect_uri를 담지 않는다", () => {
    const url = loginAuthorizeUrl("kakao");

    expect(url).not.toContain("client_id");
    expect(url).not.toContain("redirect_uri");
    expect(url).not.toContain("kauth.kakao.com");
  });

  it("백엔드 리다이렉트 엔드포인트를 가리킨다", () => {
    expect(loginAuthorizeUrl("kakao")).toContain("/api/v1/auth/kakao/authorize");
    expect(loginAuthorizeUrl("google")).toContain("/api/v1/auth/google/authorize");
  });

  it("발급한 state를 쿼리에 싣고, 콜백에서 그 값으로만 통과시킨다", () => {
    const state = stateOf(loginAuthorizeUrl("kakao"));

    expect(state).toMatch(/^[0-9a-f]{64}$/);
    expect(consumeOAuthRequest("kakao", state)).toBe("login");
  });

  it("state는 1회용이라 같은 값을 다시 내밀면 거부한다", () => {
    const state = stateOf(loginAuthorizeUrl("kakao"));

    expect(consumeOAuthRequest("kakao", state)).toBe("login");
    expect(consumeOAuthRequest("kakao", state)).toBeNull();
  });

  it("다른 제공자의 state로는 통과하지 못한다", () => {
    const kakaoState = stateOf(loginAuthorizeUrl("kakao"));

    expect(consumeOAuthRequest("google", kakaoState)).toBeNull();
  });

  it("state가 없으면 거부한다", () => {
    loginAuthorizeUrl("kakao");

    expect(consumeOAuthRequest("kakao", null)).toBeNull();
  });

  // 연동은 로그인과 콜백 주소가 같다(제공자에 등록한 redirect_uri가 하나뿐이다).
  // 이 값이 살아 돌아오지 않으면 콜백이 연동 요청을 로그인으로 처리해, 사용자는
  // 연동을 눌렀는데 "연동된 계정이 없다"는 거절만 받게 된다.
  it("연동으로 시작하면 콜백에서 link 모드로 돌아온다", () => {
    const state = stateOf(linkAuthorizeUrl("kakao"));

    expect(consumeOAuthRequest("kakao", state)).toBe("link");
  });

  it("모드는 state 문자열에 섞이지 않는다 — 서버의 형식 검증을 건드리지 않기 위해", () => {
    // 서버 STATE_PATTERN: [A-Za-z0-9_-]{16,128}
    expect(stateOf(linkAuthorizeUrl("google"))).toMatch(/^[A-Za-z0-9_-]{16,128}$/);
  });

  it("로그인과 연동은 같은 authorize 엔드포인트를 쓴다", () => {
    expect(linkAuthorizeUrl("kakao")).toContain("/api/v1/auth/kakao/authorize");
  });

  it("저장된 값이 깨져 있으면 예외 대신 거부로 끝난다", () => {
    // sessionStorage는 같은 오리진의 아무 스크립트나 쓸 수 있고, 배포 직후에는
    // 이전 버전이 남긴 형식이 들어 있을 수도 있다. 여기서 throw하면 콜백 화면이 통째로 죽는다.
    sessionStorage.setItem("oauth_request_kakao", "not-json");

    expect(consumeOAuthRequest("kakao", "whatever")).toBeNull();
  });

  // 운영은 nginx 동일 출처라 base가 빈 문자열이고, 그때 상대 경로가 나와야 한다.
  it("API base가 비어 있으면 상대 경로가 된다", () => {
    vi.stubEnv("VITE_API_BASE_URL", "");

    expect(loginAuthorizeUrl("kakao")).toMatch(/^\/api\/v1\/auth\/kakao\/authorize\?state=/);
  });
});
