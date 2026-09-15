import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import { fetchHistoryCalendar } from "../api/history";
import VisitCalendar from "./VisitCalendar";
import { calendarDays, currentKstMonth, groupCalendarEntries } from "./visitCalendarUtils";

vi.mock("../api/history", () => ({ fetchHistoryCalendar: vi.fn() }));
const fetchMock = vi.mocked(fetchHistoryCalendar);

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue({ month: currentKstMonth(), entries: [], truncated: false });
});

describe("달력 계산", () => {
  it("윤년 2월과 월요일 시작 월을 올바르게 배치한다", () => {
    const weeks = calendarDays("2024-02");
    expect(weeks.flat().filter(Boolean)).toHaveLength(29);
    expect(weeks[0]).toEqual([null, null, null, null, 1, 2, 3]);
    expect(calendarDays("2021-02")[0]).toEqual([null, 1, 2, 3, 4, 5, 6]);
  });

  it("브라우저 timezone 변환 없이 LocalDateTime 문자열 날짜로 묶는다", () => {
    const grouped = groupCalendarEntries([{ id: 1, menuName: null, restaurantName: null, visitedAt: "2026-01-01T00:05:00" }]);
    expect(grouped.get(1)?.[0].id).toBe(1);
    expect(grouped.has(31)).toBe(false);
  });

  it("KST 경계에서 현재 월을 구한다", () => {
    expect(currentKstMonth(new Date("2026-08-31T15:30:00Z"))).toBe("2026-09");
  });
});

describe("방문 기록 달력 화면", () => {
  it("시맨틱 표와 삭제된 항목 폴백을 표시한다", async () => {
    const month = currentKstMonth();
    fetchMock.mockResolvedValue({ month, entries: [{ id: 1, menuName: null, restaurantName: null, visitedAt: `${month}-01T00:05:00` }], truncated: false });
    renderWithProviders(<VisitCalendar />);
    const table = await screen.findByRole("table", { name: /방문했어요 기록/ });
    expect(table).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "일" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: /1일, 방문 기록 1개/ })).toHaveTextContent("삭제된 메뉴삭제되었거나 기록되지 않은 식당");
  });

  it("이전 달로 이동하고 현재 달에서는 다음 달을 막는다", async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation(async (month) => ({ month, entries: [], truncated: false }));
    renderWithProviders(<VisitCalendar />);
    expect(screen.getByRole("button", { name: "다음 달" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "이전 달" }));
    expect(await screen.findByRole("button", { name: "다음 달" })).toBeEnabled();
    expect(fetchMock).toHaveBeenCalledWith(expect.not.stringMatching(currentKstMonth()), expect.any(AbortSignal));
  });

  it("조회 가능한 최초 월에서는 이전 달 요청을 막는다", async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation(async (month) => ({ month, entries: [], truncated: false }));
    renderWithProviders(<VisitCalendar initialMonth="2000-01" />);
    const previous = screen.getByRole("button", { name: "이전 달" });
    expect(previous).toBeDisabled();
    await user.click(previous);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenNthCalledWith(1, "2000-01", expect.any(AbortSignal));
  });

  it("빈 상태와 잘림 안내를 독립적으로 보여준다", async () => {
    fetchMock.mockResolvedValue({ month: currentKstMonth(), entries: [], truncated: true });
    renderWithProviders(<VisitCalendar />);
    expect(await screen.findByText(/표시할 방문 기록이 없어요/)).toBeInTheDocument();
    expect(screen.getByRole("status", { name: "" })).toHaveTextContent("최근 500개");
  });

  it("오류를 목록과 무관한 경고로 보여준다", async () => {
    fetchMock.mockRejectedValue(new Error("실패"));
    renderWithProviders(<VisitCalendar />);
    expect(await screen.findByRole("alert")).toHaveTextContent("달력을 불러오지 못했습니다");
  });
});
