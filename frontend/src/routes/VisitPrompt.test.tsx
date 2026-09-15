import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import VisitPrompt from "./VisitPrompt";
import { fetchHistories, markVisited, type HistorySummary } from "../api/history";
import { readDismissedVisitPrompts } from "./visitPromptRules";

vi.mock("../api/history", () => ({ fetchHistories: vi.fn(), markVisited: vi.fn() }));

const fetchMock = vi.mocked(fetchHistories);
const visitMock = vi.mocked(markVisited);

/** 2026-09-16 13:00 KST */
const NOW = new Date(Date.UTC(2026, 8, 16, 4, 0, 0));

const pick = (overrides: Partial<HistorySummary> = {}): HistorySummary => ({
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

beforeEach(() => {
  // Date만 고정한다. 타이머까지 가짜로 바꾸면 userEvent와 react-query의 비동기 처리가 멈춘다.
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(NOW);
  window.localStorage.clear();
  fetchMock.mockReset();
  visitMock.mockReset();
  fetchMock.mockResolvedValue({ histories: [pick()], nextCursor: null, hasNext: false });
  visitMock.mockResolvedValue(undefined);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("지난번 픽 방문 확인", () => {
  it("최근 이틀 중 가장 최근 픽 하나만 불러와 묻는다", async () => {
    renderWithProviders(<VisitPrompt hidden={false} />);

    expect(
      await screen.findByRole("heading", { name: "오늘 오후 12:00에 뽑은 김치찌개, 드셨어요?" }),
    ).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(undefined, 2, 1);
  });

  it("먹었어요를 누르면 방문 처리하고, 어디서 볼 수 있는지 알려준다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<VisitPrompt hidden={false} />);

    await user.click(await screen.findByRole("button", { name: "먹었어요" }));

    await waitFor(() => expect(visitMock).toHaveBeenCalledWith(10));
    const done = await screen.findByRole("status");
    expect(done).toHaveTextContent("기록했어요");
    expect(screen.getByRole("link", { name: "히스토리" })).toHaveAttribute("href", "/history");
    // 방금 누른 버튼이 사라지므로 초점이 결과 문장으로 옮겨 가야 한다.
    expect(done).toHaveFocus();
    // 기록이 끝났다고 말한 뒤 질문을 다시 불러와 카드를 지우지 않는다.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  /** 안 먹은 것과 싫은 것은 다르다 — 거절로 기록하면 개인화 점수가 깎인다. */
  it("안 먹었어요는 서버에 아무것도 기록하지 않고 다시 묻지 않게만 한다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<VisitPrompt hidden={false} />);

    await user.click(await screen.findByRole("button", { name: "안 먹었어요" }));

    expect(visitMock).not.toHaveBeenCalled();
    expect(readDismissedVisitPrompts()).toEqual([10]);
    const done = screen.getByRole("status");
    expect(done).toHaveTextContent("다시 묻지 않을게요");
    expect(done).toHaveFocus();
  });

  it("방문 처리에 실패하면 이유를 보여주고 다시 누를 수 있게 둔다", async () => {
    const user = userEvent.setup();
    visitMock.mockRejectedValueOnce(new Error("일시적인 오류"));
    renderWithProviders(<VisitPrompt hidden={false} />);

    await user.click(await screen.findByRole("button", { name: "먹었어요" }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "먹었어요" })).toBeInTheDocument();
  });

  it("물을 픽이 없으면 아무것도 그리지 않는다", async () => {
    fetchMock.mockResolvedValue({ histories: [pick({ isVisited: true })], nextCursor: null, hasNext: false });
    const { container } = renderWithProviders(<VisitPrompt hidden={false} />);

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  /** 불러오기 실패로 픽 화면 맨 위에 오류를 띄우면, 곁다리 질문이 본 동작을 가린다. */
  it("불러오기에 실패해도 오류를 띄우지 않는다", async () => {
    fetchMock.mockRejectedValue(new Error("네트워크"));
    const { container } = renderWithProviders(<VisitPrompt hidden={false} />);

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  /** 새 픽이 생기면 "지난번"이 바뀐다. 뽑는 동안 옛 픽을 계속 묻지 않는다. */
  it("뽑기를 시작하면 질문을 거둔다", async () => {
    const user = userEvent.setup();
    // rerender는 renderWithProviders의 Provider까지 갈아 끼우므로, 상태로 hidden을 바꾼다.
    function Harness() {
      const [hidden, setHidden] = useState(false);
      return (
        <>
          <button type="button" onClick={() => setHidden(true)}>뽑기</button>
          <VisitPrompt hidden={hidden} />
        </>
      );
    }
    renderWithProviders(<Harness />);
    await screen.findByRole("button", { name: "먹었어요" });

    await user.click(screen.getByRole("button", { name: "뽑기" }));

    expect(screen.queryByRole("button", { name: "먹었어요" })).toBeNull();
  });
});
