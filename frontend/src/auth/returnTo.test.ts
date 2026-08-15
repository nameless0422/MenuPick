import { beforeEach, describe, expect, it } from "vitest";
import { consumeReturnTo, rememberReturnTo } from "./returnTo";

const FALLBACK = "/menus";

beforeEach(() => {
  sessionStorage.clear();
});

describe("consumeReturnTo", () => {
  it("기억해 둔 경로로 돌려보낸다", () => {
    rememberReturnTo("/history?days=30");

    expect(consumeReturnTo(FALLBACK)).toBe("/history?days=30");
  });

  it("한 번 쓰면 지워진다 — 나중에 직접 /login으로 들어온 사용자가 옛 화면으로 튕기지 않는다", () => {
    rememberReturnTo("/history");

    expect(consumeReturnTo(FALLBACK)).toBe("/history");
    expect(consumeReturnTo(FALLBACK)).toBe(FALLBACK);
  });

  it("막힌 적이 없으면 기본 착지점으로 보낸다", () => {
    expect(consumeReturnTo(FALLBACK)).toBe(FALLBACK);
  });

  // sessionStorage는 같은 오리진의 어떤 스크립트든 쓸 수 있어, 여기 담긴 값을 우리가
  // 넣었다고 가정할 수 없다. 아래 값들은 경로처럼 생겼지만 그대로 따라가면 외부 사이트로
  // 나가는 오픈 리다이렉트가 된다 — 로그인 직후라 피싱 페이지의 신뢰도가 가장 높은 순간이다.
  it.each([
    ["프로토콜 상대 URL", "//evil.com"],
    ["역슬래시로 위장한 프로토콜 상대 URL", "/\\evil.com"],
    ["절대 URL", "https://evil.com"],
    ["경로가 아닌 값", "evil.com"],
    ["빈 값", ""],
  ])("%s은(는) 무시하고 기본 착지점으로 보낸다", (_, stored) => {
    sessionStorage.setItem("returnTo", stored);

    expect(consumeReturnTo(FALLBACK)).toBe(FALLBACK);
  });

  it("거부한 값도 저장소에서 지운다 — 남겨두면 다음 로그인에서 또 검사에 걸린다", () => {
    sessionStorage.setItem("returnTo", "//evil.com");

    consumeReturnTo(FALLBACK);

    expect(sessionStorage.getItem("returnTo")).toBeNull();
  });
});
