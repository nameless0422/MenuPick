import { beforeEach, describe, expect, it, vi } from "vitest";
import { consumeState, googleAuthorizeUrl, kakaoAuthorizeUrl } from "./oauthUrls";

describe("oauthUrls", () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.unstubAllEnvs();
  });

  // 이 파일의 존재 이유 자체다 — client_id가 번들에 들어가면 카카오 REST API 키가 공개된다.
  it("인가 주소에 client_id·redirect_uri를 담지 않는다", () => {
    const url = kakaoAuthorizeUrl();

    expect(url).not.toContain("client_id");
    expect(url).not.toContain("redirect_uri");
    expect(url).not.toContain("kauth.kakao.com");
  });

  it("백엔드 리다이렉트 엔드포인트를 가리킨다", () => {
    expect(kakaoAuthorizeUrl()).toContain("/api/v1/auth/kakao/authorize");
    expect(googleAuthorizeUrl()).toContain("/api/v1/auth/google/authorize");
  });

  it("발급한 state를 쿼리에 싣고, 콜백에서 그 값으로만 통과시킨다", () => {
    const url = kakaoAuthorizeUrl();
    const state = new URL(url, "http://localhost").searchParams.get("state");

    expect(state).toMatch(/^[0-9a-f]{64}$/);
    expect(consumeState("kakao", state)).toBe(true);
  });

  it("state는 1회용이라 같은 값을 다시 내밀면 거부한다", () => {
    const state = new URL(kakaoAuthorizeUrl(), "http://localhost").searchParams.get("state");

    expect(consumeState("kakao", state)).toBe(true);
    expect(consumeState("kakao", state)).toBe(false);
  });

  it("다른 제공자의 state로는 통과하지 못한다", () => {
    const kakaoState = new URL(kakaoAuthorizeUrl(), "http://localhost").searchParams.get("state");

    expect(consumeState("google", kakaoState)).toBe(false);
  });

  // 운영은 nginx 동일 출처라 base가 빈 문자열이고, 그때 상대 경로가 나와야 한다.
  it("API base가 비어 있으면 상대 경로가 된다", () => {
    vi.stubEnv("VITE_API_BASE_URL", "");

    expect(kakaoAuthorizeUrl()).toMatch(/^\/api\/v1\/auth\/kakao\/authorize\?state=/);
  });
});
