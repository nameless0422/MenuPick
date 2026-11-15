import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders } from "../test/renderWithProviders";
import { fetchNutritionSummary, type NutritionSummary as Summary } from "../api/nutrition";
import NutritionSummary from "./NutritionSummary";

vi.mock("../api/nutrition", () => ({ fetchNutritionSummary: vi.fn() }));
const fetchMock = vi.mocked(fetchNutritionSummary);

const summary = (overrides: Partial<Summary> = {}): Summary => ({
  periodDays: 3,
  days: [
    { date: "2026-10-07", kcal: 0, meals: 0, unknownMeals: 0 },
    { date: "2026-10-08", kcal: 600, meals: 2, unknownMeals: 0 },
    { date: "2026-10-09", kcal: 750, meals: 2, unknownMeals: 1 },
  ],
  slots: [
    { slot: "BREAKFAST", kcal: 0, meals: 0 },
    { slot: "LUNCH", kcal: 1050, meals: 2 },
    { slot: "DINNER", kcal: 300, meals: 1 },
    { slot: "SNACK", kcal: 0, meals: 0 },
  ],
  totalKcal: 1350, knownMeals: 3, unknownMeals: 1, averageKcal: 675, truncated: false,
  ...overrides,
});

beforeEach(() => fetchMock.mockReset().mockResolvedValue(summary()));

describe("열량 기록", () => {
  it("하루 평균과 날짜별 값을 추정치로 보여 주고, 기록 없는 날은 0kcal이 아니라 기록 없음이다", async () => {
    renderWithProviders(<NutritionSummary />);
    expect(await screen.findByText(/하루 평균/)).toHaveTextContent("약 675kcal");
    const days = within(screen.getByRole("list", { name: "최근 3일 하루 열량" })).getAllByRole("listitem");
    // 2026-10-07은 수요일이다. 시간대와 무관해야 한다.
    expect(days[0]).toHaveTextContent("10/7 (수)기록 없음");
    expect(days[1]).toHaveTextContent("약 600kcal");
    expect(days[2]).toHaveTextContent("약 750kcal · 모름 1끼");
  });

  it("먹은 끼니만 끼니별로 보여 주고, 모르는 끼니가 있으면 메뉴에 열량을 정하러 가게 한다", async () => {
    renderWithProviders(<NutritionSummary />);
    const slots = await screen.findByRole("list", { name: "끼니별 열량" });
    expect(within(slots).getAllByRole("listitem").map((li) => li.textContent))
      .toEqual(["점심 약 1050kcal", "저녁 약 300kcal"]);
    expect(screen.getByText(/열량을 모르는 끼니 1번은 합계에서 빠졌어요/)).toBeVisible();
    expect(screen.getByRole("link", { name: "메뉴에 열량 정하기 →" })).toHaveAttribute("href", "/menus");
  });

  it("아는 끼니가 없으면 숫자 대신 무엇을 하면 채워지는지 말한다", async () => {
    fetchMock.mockResolvedValue(summary({ knownMeals: 0, unknownMeals: 2, totalKcal: 0, averageKcal: null }));
    renderWithProviders(<NutritionSummary />);
    expect(await screen.findByText(/2끼 모두 열량을 모르는 메뉴예요/)).toBeVisible();
    expect(screen.queryByText(/하루 평균/)).not.toBeInTheDocument();
  });
});
