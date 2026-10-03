import { describe, expect, it } from "vitest";
import { PAUSE_DAY_OPTIONS, isPaused, pauseLabel, pauseOptionLabel } from "./menuPause";

/** KST 시각을 epoch ms로. 테스트가 어느 시간대에서 돌아도 같은 값이어야 한다. */
const kst = (iso: string) => Date.parse(`${iso}+09:00`);

describe("isPaused", () => {
  it("null·빈 값은 쉬지 않는 것으로 읽는다", () => {
    expect(isPaused(null, kst("2026-01-15T12:00:00"))).toBe(false);
    expect(isPaused(undefined, kst("2026-01-15T12:00:00"))).toBe(false);
  });

  /**
   * 서버는 기간이 지나도 pausedUntil을 지우지 않는다. 값이 있다는 것만으로 "쉬는 중"으로
   * 읽으면 이미 깨어난 메뉴에 배지가 영구히 붙는다.
   */
  it("지난 시각은 이미 깨어난 것이다", () => {
    expect(isPaused("2026-01-10T12:00:00", kst("2026-01-15T12:00:00"))).toBe(false);
  });

  it("경계(같은 시각)는 깨어난 것으로 본다 — 서버의 paused_until <= now와 같은 기준", () => {
    expect(isPaused("2026-01-15T12:00:00", kst("2026-01-15T12:00:00"))).toBe(false);
    expect(isPaused("2026-01-15T12:00:01", kst("2026-01-15T12:00:00"))).toBe(true);
  });

  /** 시간대 의존이 섞이면 CI(UTC)와 로컬(KST)의 결과가 갈린다 — 2026-09-19에 실제로 겪었다. */
  it("서버 문자열을 KST로 읽는다", () => {
    // UTC로 읽으면 이 시각은 아직 미래(= 쉬는 중)로 보인다. KST로 읽으면 이미 지났다.
    expect(isPaused("2026-01-15T12:00:00", kst("2026-01-15T14:00:00"))).toBe(false);
  });
});

describe("pauseLabel", () => {
  it("남은 날짜를 달력 기준으로 센다", () => {
    expect(pauseLabel("2026-01-18T00:30:00", kst("2026-01-15T00:30:00"))).toBe("쉬는 중 · 3일 남음");
  });

  /** 24시간 단위로 세면 "오늘 밤까지"가 0일 남음이 되어 사라진 것처럼 보인다. */
  it("자정을 넘지 않으면 오늘까지라고 말한다", () => {
    expect(pauseLabel("2026-01-15T23:00:00", kst("2026-01-15T09:00:00"))).toBe("쉬는 중 · 오늘까지");
  });

  it("쉬지 않으면 null이다", () => {
    expect(pauseLabel(null, kst("2026-01-15T09:00:00"))).toBeNull();
    expect(pauseLabel("2026-01-01T09:00:00", kst("2026-01-15T09:00:00"))).toBeNull();
  });
});

describe("기간 선택지", () => {
  it("사용자가 떠올리는 단위로 이름을 붙인다", () => {
    expect(PAUSE_DAY_OPTIONS).toEqual([3, 7, 14, 30]);
    expect(PAUSE_DAY_OPTIONS.map(pauseOptionLabel)).toEqual(["3일", "1주", "2주", "1달"]);
  });

  /** 서버 상한은 90일이다. 그보다 긴 기간은 "잠시"가 아니라 추천 제외다. */
  it("서버가 받는 범위 안이다", () => {
    expect(Math.min(...PAUSE_DAY_OPTIONS)).toBeGreaterThanOrEqual(1);
    expect(Math.max(...PAUSE_DAY_OPTIONS)).toBeLessThanOrEqual(90);
  });
});
