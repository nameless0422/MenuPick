import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchTrends, type TrendsResponse } from "../api/trends";
import { renderWithProviders } from "../test/renderWithProviders";
import PickTrends from "./PickTrends";
import {
  TREND_MAX_AGE_MS,
  addTrendCategory,
  trendComputedAtMillis,
  validateTrendsResponse,
} from "./trendValidation";

vi.mock("../api/trends", () => ({ fetchTrends: vi.fn() }));
const fetchTrendsMock = vi.mocked(fetchTrends);
const baseNow = Date.parse("2026-09-13T12:00:00+09:00");
const ready: TrendsResponse = {
  status: "READY",
  windowDays: 7,
  minUsers: 5,
  maxLabels: 5,
  computedAt: "2026-09-13T11:00:00",
  categories: [{ label: "한식", userCount: 12, rankOrder: 1 }],
  menus: [{ label: "김치찌개", userCount: 9, rankOrder: 1 }],
};

beforeEach(() => {
  vi.spyOn(Date, "now").mockReturnValue(baseNow);
  fetchTrendsMock.mockReset();
  fetchTrendsMock.mockResolvedValue(ready);
});

afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("트렌드 응답 경계", () => {
  it("KST LocalDateTime을 엄격하게 해석한다", () => {
    expect(trendComputedAtMillis("2026-09-13T11:00:00")).toBe(
      Date.parse("2026-09-13T11:00:00+09:00"),
    );
    expect(trendComputedAtMillis("2026-02-30T11:00:00")).toBeNull();
    expect(trendComputedAtMillis("2026-09-13T25:00:00")).toBeNull();
  });

  it("잘못된 DTO와 20개를 넘는 서버 상한은 fail-closed 처리한다", () => {
    expect(validateTrendsResponse({ ...ready, categories: "한식" })).toBeNull();
    expect(validateTrendsResponse({ ...ready, maxLabels: 21 })).toBeNull();
    expect(validateTrendsResponse({
      ...ready,
      categories: [{ label: "한식", userCount: -1, rankOrder: 1 }],
    })).toBeNull();
  });

  it("수동 카테고리는 중복 없이 더하되 20개 상한을 넘지 않는다", () => {
    expect(addTrendCategory(["한식"], "한식")).toEqual(["한식"]);
    const full = Array.from({ length: 20 }, (_, index) => `분류${index}`);
    expect(addTrendCategory(full, "한식")).toBe(full);
  });
});

it.each(["DISABLED", "ERROR"])("%s이면 패널을 숨겨 픽 흐름을 막지 않는다", async (kind) => {
  if (kind === "ERROR") fetchTrendsMock.mockRejectedValue(new Error("network"));
  else fetchTrendsMock.mockResolvedValue({ ...ready, status: "DISABLED", computedAt: null });

  renderWithProviders(<PickTrends busy={false} onPickCategory={vi.fn()} />);
  await act(async () => { await Promise.resolve(); });
  expect(screen.queryByRole("heading", { name: "최근 선택·방문한 메뉴" })).not.toBeInTheDocument();
});

it.each([
  ["NOT_READY", "아직 트렌드를 보여드릴 만큼"],
  ["STALE", "트렌드를 새로 집계하고 있어요"],
] as const)("%s 상태를 인원 수 없이 정직하게 안내한다", async (status, message) => {
  fetchTrendsMock.mockResolvedValue({
    ...ready,
    status,
    computedAt: status === "NOT_READY" ? null : ready.computedAt,
  });
  renderWithProviders(<PickTrends busy={false} onPickCategory={vi.fn()} />);

  expect(await screen.findByText(new RegExp(message))).toBeInTheDocument();
  expect(screen.queryByText("12명")).not.toBeInTheDocument();
});

it("READY 목록은 사람 수와 KST 시각을 표시하고 정식 카테고리만 동작시킨다", async () => {
  fetchTrendsMock.mockResolvedValue({
    ...ready,
    categories: [
      { label: "서버임의분류", userCount: 20, rankOrder: 1 },
      { label: "한식", userCount: 12, rankOrder: 2 },
    ],
  });
  const onPickCategory = vi.fn();
  const user = userEvent.setup();
  renderWithProviders(<PickTrends busy={false} onPickCategory={onPickCategory} />);

  expect(await screen.findByText("김치찌개")).toBeInTheDocument();
  expect(screen.getByText("12명")).toBeInTheDocument();
  expect(screen.getByText(/KST 집계/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /서버임의분류/ })).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: /한식.*12명/ }));
  expect(onPickCategory).toHaveBeenCalledWith("한식");
});

it("READY 빈 결과는 서버의 최소 인원을 그대로 안내한다", async () => {
  fetchTrendsMock.mockResolvedValue({ ...ready, categories: [], menus: [], minUsers: 7 });
  renderWithProviders(<PickTrends busy={false} onPickCategory={vi.fn()} />);
  expect(await screen.findByText(/사용자 7명 이상/)).toBeInTheDocument();
});

it("미래 또는 36시간을 넘긴 READY 집계는 숨긴다", async () => {
  for (const computedAt of ["2026-09-13T12:00:01", "2026-09-11T23:59:59"]) {
    fetchTrendsMock.mockResolvedValue({ ...ready, computedAt });
    const view = renderWithProviders(<PickTrends busy={false} onPickCategory={vi.fn()} />);
    await act(async () => { await Promise.resolve(); });
    expect(screen.queryByText("김치찌개")).not.toBeInTheDocument();
    view.unmount();
  }
});

it("다른 픽이 진행 중이면 카테고리 동작을 막는다", async () => {
  renderWithProviders(<PickTrends busy onPickCategory={vi.fn()} />);
  expect(await screen.findByRole("button", { name: /한식.*12명/ })).toBeDisabled();
});

it("36시간 만료 시 즉시 숨기고 재조회하며 타이머를 정리한다", async () => {
  vi.restoreAllMocks();
  vi.useFakeTimers();
  vi.setSystemTime(baseNow);
  const almostExpired = new Date(baseNow + 9 * 60 * 60 * 1000 - TREND_MAX_AGE_MS + 1_000)
    .toISOString().slice(0, 19);
  fetchTrendsMock.mockReset();
  fetchTrendsMock.mockResolvedValueOnce({ ...ready, computedAt: almostExpired });
  fetchTrendsMock.mockRejectedValueOnce(new Error("refresh failed"));
  const view = renderWithProviders(<PickTrends busy={false} onPickCategory={vi.fn()} />);
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  expect(screen.getByText("김치찌개")).toBeInTheDocument();

  await act(async () => { await vi.advanceTimersByTimeAsync(1_001); });
  expect(fetchTrendsMock).toHaveBeenCalledTimes(2);
  expect(screen.queryByText("김치찌개")).not.toBeInTheDocument();

  view.unmount();
  await act(async () => { await vi.advanceTimersByTimeAsync(TREND_MAX_AGE_MS); });
  expect(fetchTrendsMock).toHaveBeenCalledTimes(2);
});
