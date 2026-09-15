import { beforeEach, describe, expect, it } from "vitest";
import type { HistorySummary } from "../api/history";
import {
  describePickedAt,
  readDismissedVisitPrompts,
  rememberDismissedVisitPrompt,
  selectVisitPromptCandidate,
} from "./visitPromptRules";

/** 2026-09-16 13:00 KST. 서버 시각 문자열은 KST LocalDateTime이다. */
const NOW = Date.UTC(2026, 8, 16, 4, 0, 0);

const history = (overrides: Partial<HistorySummary> = {}): HistorySummary => ({
  id: 10,
  menuName: "김치찌개",
  restaurantName: null,
  isVisited: false,
  recommendedAt: "2026-09-16T12:00:00",
  visitedAt: null,
  recommendationFeedback: null,
  filterConditions: [],
  ...overrides,
});

describe("방문 확인 대상 고르기", () => {
  it("한 시간 전 뽑고 기록이 없는 픽을 묻는다", () => {
    expect(selectVisitPromptCandidate([history()], NOW, [])?.id).toBe(10);
  });

  /**
   * 한 끼에 다시 돌리기로 기록이 여러 줄 생긴다. 최신 픽이 이미 처리됐는데 그 이전 것을
   * 대신 물으면, 넘긴 메뉴를 두고 "드셨어요?"가 된다.
   */
  it("가장 최근 픽이 이미 방문 처리됐으면 그 이전 픽을 대신 묻지 않는다", () => {
    const latest = history({ id: 11, isVisited: true, visitedAt: "2026-09-16T12:40:00" });
    const older = history({ id: 10 });
    expect(selectVisitPromptCandidate([latest, older], NOW, [])).toBeNull();
  });

  it("거절한 픽은 묻지 않는다 — 방금 한 대답을 무시하게 된다", () => {
    expect(
      selectVisitPromptCandidate([history({ recommendationFeedback: "REJECTED" })], NOW, []),
    ).toBeNull();
  });

  it("수락한 픽은 묻는다 — 고른 것과 먹은 것은 다르다", () => {
    expect(
      selectVisitPromptCandidate([history({ recommendationFeedback: "ACCEPTED" })], NOW, []),
    ).not.toBeNull();
  });

  it("메뉴가 지워져 이름이 없으면 묻지 않는다", () => {
    expect(selectVisitPromptCandidate([history({ menuName: null })], NOW, [])).toBeNull();
  });

  it("이미 안 먹었다고 답한 픽은 다시 묻지 않는다", () => {
    expect(selectVisitPromptCandidate([history()], NOW, [10])).toBeNull();
  });

  it("뽑은 지 30분이 안 됐으면 아직 묻지 않는다", () => {
    expect(
      selectVisitPromptCandidate([history({ recommendedAt: "2026-09-16T12:40:00" })], NOW, []),
    ).toBeNull();
    expect(
      selectVisitPromptCandidate([history({ recommendedAt: "2026-09-16T12:30:00" })], NOW, []),
    ).not.toBeNull();
  });

  it("48시간이 지난 픽은 묻지 않는다", () => {
    expect(
      selectVisitPromptCandidate([history({ recommendedAt: "2026-09-14T12:59:00" })], NOW, []),
    ).toBeNull();
    expect(
      selectVisitPromptCandidate([history({ recommendedAt: "2026-09-14T13:00:00" })], NOW, []),
    ).not.toBeNull();
  });

  it("기록이 없거나 시각을 읽을 수 없으면 묻지 않는다", () => {
    expect(selectVisitPromptCandidate([], NOW, [])).toBeNull();
    expect(selectVisitPromptCandidate([history({ recommendedAt: "어제" })], NOW, [])).toBeNull();
  });
});

describe("언제 뽑았는지 말하기", () => {
  it("같은 KST 날짜면 오늘, 하루 전이면 어제라고 한다", () => {
    expect(describePickedAt("2026-09-16T12:31:00", NOW)).toMatch(/^오늘 오후 12:31$/);
    expect(describePickedAt("2026-09-15T19:05:00", NOW)).toMatch(/^어제 오후 7:05$/);
  });

  /** 24시간 차이로 가르면 어젯밤 11시 픽을 새벽 1시에 "오늘"이라고 부르게 된다. */
  it("24시간이 안 지났어도 날짜가 바뀌었으면 어제다", () => {
    const earlyMorning = Date.UTC(2026, 8, 15, 16, 0, 0); // 09-16 01:00 KST
    expect(describePickedAt("2026-09-15T23:00:00", earlyMorning)).toMatch(/^어제 /);
  });

  it("자정과 정오는 12시로 말한다", () => {
    expect(describePickedAt("2026-09-16T00:05:00", NOW)).toBe("오늘 오전 12:05");
    expect(describePickedAt("2026-09-16T12:00:00", NOW)).toBe("오늘 오후 12:00");
  });

  it("그보다 전이면 날짜로 말한다", () => {
    expect(describePickedAt("2026-09-14T12:31:00", NOW)).toMatch(/^9월 14일 오후 12:31$/);
  });
});

describe("안 먹었어요 기억하기", () => {
  beforeEach(() => window.localStorage.clear());

  it("답한 픽을 기억하고 최신 것부터 20개까지만 둔다", () => {
    for (let id = 1; id <= 25; id++) rememberDismissedVisitPrompt(id);
    const remembered = readDismissedVisitPrompts();
    expect(remembered).toHaveLength(20);
    expect(remembered[0]).toBe(25);
    expect(remembered).not.toContain(5);
  });

  /** 저장소 내용은 이 앱 밖에서도 바뀔 수 있다. 깨진 값 때문에 픽 화면이 죽으면 안 된다. */
  it("저장된 값이 깨져 있으면 빈 목록으로 본다", () => {
    window.localStorage.setItem("menupick.visitPrompt.dismissed", "{not json");
    expect(readDismissedVisitPrompts()).toEqual([]);
    window.localStorage.setItem("menupick.visitPrompt.dismissed", JSON.stringify(["x", 3, 1.5]));
    expect(readDismissedVisitPrompts()).toEqual([3]);
  });
});
