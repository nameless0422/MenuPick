import { describe, expect, it } from "vitest";
import { starToggle } from "./starToggle";

/**
 * 이 헬퍼가 존재하는 이유가 곧 이 테스트가 지키는 것이다 — 클래스와 aria가 어긋나지 않는 것.
 * 둘을 호출부에서 따로 쓰면 클래스 조건만 손대고 aria를 빼먹기 쉽고, 그때 생기는 버그는
 * 화면상으로는 멀쩡해 눈으로 절대 찾을 수 없다.
 */
describe("starToggle", () => {
  it("채워진 별은 on 클래스와 aria-pressed=true를 함께 준다", () => {
    expect(starToggle(true)).toEqual({ className: "star on", "aria-pressed": true });
  });

  it("빈 별은 둘 다 떨어진다", () => {
    expect(starToggle(false)).toEqual({ className: "star", "aria-pressed": false });
  });
});
