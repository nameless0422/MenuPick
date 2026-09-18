import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "../test/renderWithProviders";
import EatingSummary from "./EatingSummary";
import { fetchEatingSummary, type EatingSummary as Summary } from "../api/history";

vi.mock("../api/history", () => ({ fetchEatingSummary: vi.fn() }));

const fetchMock = vi.mocked(fetchEatingSummary);

/** 2026-09-19 12:00 KST */
const NOW = new Date(Date.UTC(2026, 8, 19, 3, 0, 0));

const summary = (overrides: Partial<Summary> = {}): Summary => ({
  periodDays: 30,
  picks: 12,
  eaten: 5,
  categories: [{ label: "한식", count: 4 }],
  menus: [{ label: "김치찌개", count: 3 }],
  forgottenMenus: [],
  ...overrides,
});

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(NOW);
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(summary());
});

afterEach(() => vi.useRealTimers());

describe("내 식사 기록 요약", () => {
  /** 비율 하나로 줄이면 "10번 뽑아 3번"과 "3번 뽑아 3번"이 같은 30%가 된다. */
  it("뽑은 수와 먹은 수를 함께 보여준다", async () => {
    renderWithProviders(<EatingSummary />);

    const line = await screen.findByText(/뽑은/);
    expect(line).toHaveTextContent("최근 30일");
    expect(line).toHaveTextContent("12번");
    expect(line).toHaveTextContent("5번");
  });

  it("많이 먹은 카테고리와 메뉴를 횟수와 함께 보여준다", async () => {
    renderWithProviders(<EatingSummary />);

    expect(await screen.findByText("많이 먹은 카테고리")).toBeInTheDocument();
    expect(screen.getByText("많이 먹은 메뉴")).toBeInTheDocument();
    expect(screen.getByText("한식")).toBeInTheDocument();
    expect(screen.getByText("김치찌개")).toBeInTheDocument();
    expect(screen.getAllByText(/[0-9]+번/).length).toBeGreaterThanOrEqual(2);
  });

  /**
   * 지금 운영에는 방문 기록이 0건이다. 빈 목록만 그려 두면 사용자는 고장으로 읽는다 —
   * 무엇을 눌러야 채워지는지 말해야 한다.
   */
  it("먹은 기록이 없으면 무엇을 하면 되는지 알려준다", async () => {
    fetchMock.mockResolvedValue(summary({ eaten: 0, categories: [], menus: [] }));
    renderWithProviders(<EatingSummary />);

    expect(await screen.findByText(/방문했어요/)).toBeInTheDocument();
    expect(screen.queryByText("많이 먹은 카테고리")).toBeNull();
  });

  it("아직 한 번도 안 뽑았으면 뽑으러 가는 링크를 준다", async () => {
    fetchMock.mockResolvedValue(summary({ picks: 0, eaten: 0, categories: [], menus: [] }));
    renderWithProviders(<EatingSummary />);

    expect(await screen.findByRole("link", { name: /먼저 한 번 뽑아보기/ }))
      .toHaveAttribute("href", "/pick");
  });

  it("오래 안 뽑힌 메뉴는 지난 날수로 말한다", async () => {
    fetchMock.mockResolvedValue(summary({
      forgottenMenus: [
        { menuId: 1, name: "제육볶음", lastPickedAt: null },
        { menuId: 2, name: "김치찌개", lastPickedAt: "2026-09-07T12:00:00" },
        { menuId: 3, name: "비빔밥", lastPickedAt: "2026-09-18T12:00:00" },
      ],
    }));
    renderWithProviders(<EatingSummary />);

    expect(await screen.findByText("아직 안 뽑힘")).toBeInTheDocument();
    expect(screen.getByText("12일 전")).toBeInTheDocument();
    expect(screen.getByText("어제")).toBeInTheDocument();
  });

  it("불러오기에 실패하면 이유를 보여준다", async () => {
    fetchMock.mockRejectedValue(new Error("네트워크"));
    renderWithProviders(<EatingSummary />);

    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
