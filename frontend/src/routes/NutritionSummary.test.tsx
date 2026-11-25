import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import { fetchNutritionSummary, type NutritionSummary as Summary } from "../api/nutrition";
import NutritionSummary from "./NutritionSummary";
import { energyShares, niceTicks, percentShares } from "./nutritionChartScale";

vi.mock("../api/nutrition", () => ({ fetchNutritionSummary: vi.fn() }));
const fetchMock = vi.mocked(fetchNutritionSummary);

function slotsOf(breakfast: number, lunch: number, dinner: number, snack: number) {
  return ([["BREAKFAST", breakfast], ["LUNCH", lunch], ["DINNER", dinner], ["SNACK", snack]] as const)
    .map(([slot, kcal]) => ({ slot, kcal, meals: kcal > 0 ? 1 : 0 }));
}

const summary = (overrides: Partial<Summary> = {}): Summary => ({
  periodDays: 3,
  days: [
    { date: "2026-10-07", kcal: 0, meals: 0, unknownMeals: 0 },
    { date: "2026-10-08", kcal: 600, meals: 2, unknownMeals: 0, slots: slotsOf(0, 300, 300, 0) },
    { date: "2026-10-09", kcal: 750, meals: 2, unknownMeals: 1, slots: slotsOf(0, 750, 0, 0) },
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

beforeEach(() => {
  fetchMock.mockReset().mockResolvedValue(summary());
  window.localStorage.clear();
});

describe("열량 기록", () => {
  it("기준선을 정했으면 열량 그래프에 점선과 범례만 더하고, 판정 문구는 없다", async () => {
    fetchMock.mockResolvedValue(summary({ dailyKcalReference: 2000 }));
    const { container } = renderWithProviders(<NutritionSummary />);

    expect(await screen.findByText("기준 2,000kcal")).toBeInTheDocument();
    expect(container.querySelector('[data-guide="reference"]')).not.toBeNull();
    expect(screen.queryByText(/초과|부족|넘었/)).not.toBeInTheDocument();

    await userEvent.setup().click(screen.getByRole("button", { name: "선" }));
    expect(container.querySelector('[data-guide="reference"]')).not.toBeNull();
  });

  it("기준선이 없으면 선을 긋지 않는다", async () => {
    const { container } = renderWithProviders(<NutritionSummary />);
    await screen.findByText(/하루 평균/);
    expect(container.querySelector('[data-guide="reference"]')).toBeNull();
    expect(container.querySelector('[data-guide="average"]')).not.toBeNull();
  });

  it("하루 평균과 날짜·끼니별 값을 표로도 말하고, 기록 없는 날은 0kcal이 아니라 기록 없음이다", async () => {
    renderWithProviders(<NutritionSummary />);
    expect(await screen.findByText(/하루 평균/)).toHaveTextContent("약 675kcal");
    const rows = within(screen.getByRole("table", { name: /최근 3일 하루 열량/ })).getAllByRole("row");
    expect(within(rows[0]).getAllByRole("columnheader").map((th) => th.textContent))
      .toEqual(["날짜", "아침", "점심", "저녁", "간식", "합계", "모름"]);
    // 2026-10-07은 수요일이다. 시간대와 무관해야 한다.
    expect(rows[1]).toHaveTextContent("10/7 (수)기록 없음");
    expect(within(rows[2]).getAllByRole("cell").map((td) => td.textContent)).toEqual(["–", "300", "300", "–", "600", "–"]);
    expect(within(rows[3]).getAllByRole("cell").map((td) => td.textContent)).toEqual(["–", "750", "–", "–", "750", "1끼"]);
  });

  it("그래프는 끼니별로 쌓고, 기록 없는 날은 막대를 그리지 않으며, 모르는 끼니가 있던 날에 ?를 단다", async () => {
    const { container } = renderWithProviders(<NutritionSummary />);
    await screen.findByText(/하루 평균/);
    const svg = container.querySelector(".nutrition-chart svg")!;
    expect(svg).toHaveAttribute("aria-hidden", "true");
    expect(svg.querySelectorAll(".nutrition-seg-lunch")).toHaveLength(2);
    expect(svg.querySelectorAll(".nutrition-seg-dinner")).toHaveLength(1);
    expect(svg.querySelectorAll(".nutrition-seg-breakfast, .nutrition-seg-snack")).toHaveLength(0);
    expect([...svg.querySelectorAll(".nutrition-chart-unknown")].map((el) => el.textContent)).toEqual(["?"]);
    expect(svg.querySelector(".nutrition-chart-average")).not.toBeNull();
    expect(within(screen.getByRole("list", { name: "그래프 색: 끼니" })).getAllByRole("listitem").map((li) => li.textContent))
      .toEqual(["아침", "점심", "저녁", "간식", "? 모르는 끼니 있음"]);

    fireEvent.mouseEnter(svg.querySelectorAll(".nutrition-chart-hit")[1]);
    expect(container.querySelector(".nutrition-chart-tooltip")).toHaveTextContent("10/8 (목)약 600kcal점심 300저녁 300");
  });

  it("탄단지 하루 평균과 열량 비율을 보여 주고, 몇 끼 기준인지 밝힌다", async () => {
    fetchMock.mockResolvedValue(summary({
      macros: { meals: 2, carbsG: 200, proteinG: 60, fatG: 50, fiberMeals: 1, fiberG: 12 },
    }));
    const { container } = renderWithProviders(<NutritionSummary />);
    expect(await screen.findByRole("heading", { name: "탄수화물·단백질·지방" })).toBeVisible();
    expect(screen.getByText(/먹은 4끼 중 2끼 기준/)).toBeVisible();
    // 800 + 240 + 450 = 1490kcal → 53.7% / 16.1% / 30.2%
    expect(within(screen.getByRole("list", { name: "탄단지 하루 평균" })).getAllByRole("listitem").map((li) => li.textContent))
      .toEqual(["탄수화물 약 200g 열량의 54%", "단백질 약 60g 열량의 16%", "지방 약 50g 열량의 30%", "식이섬유 약 12g (1끼 기준)"]);
    expect(container.querySelector(".nutrition-macro-bar")).toHaveAttribute("aria-hidden", "true");
  });

  it("탄단지를 아는 끼니가 없으면 어디서 채우는지 알려 주고, 예전 서버면 칸을 숨긴다", async () => {
    fetchMock.mockResolvedValue(summary({
      macros: { meals: 0, carbsG: null, proteinG: null, fatG: null, fiberMeals: 0, fiberG: null },
    }));
    renderWithProviders(<NutritionSummary />);
    expect(await screen.findByText(/탄단지를 아는 메뉴로 먹은 끼니가 없어요/)).toBeVisible();
    expect(screen.getByRole("link", { name: "메뉴의 열량 칸" })).toHaveAttribute("href", "/menus");
  });

  it("당류·나트륨 하루 평균도 보여 주고, 탄단지를 몰라도 아는 값은 보인다", async () => {
    fetchMock.mockResolvedValue(summary({
      macros: { meals: 2, carbsG: 200, proteinG: 60, fatG: 50, fiberMeals: 1, fiberG: 12,
        sugarsMeals: 2, sugarsG: 28, sodiumMeals: 3, sodiumMg: 2960 },
    }));
    const { unmount } = renderWithProviders(<NutritionSummary />);
    const list = await screen.findByRole("list", { name: "탄단지 하루 평균" });
    expect(within(list).getAllByRole("listitem").slice(3).map((li) => li.textContent))
      .toEqual(["식이섬유 약 12g (1끼 기준)", "당류 약 28g", "나트륨 약 2,960mg (3끼 기준)"]);
    unmount();

    fetchMock.mockResolvedValue(summary({
      macros: { meals: 0, carbsG: null, proteinG: null, fatG: null, fiberMeals: 0, fiberG: null,
        sugarsMeals: 1, sugarsG: 25, sodiumMeals: 0, sodiumMg: null },
    }));
    renderWithProviders(<NutritionSummary />);
    expect(await screen.findByText(/탄단지를 아는 메뉴로 먹은 끼니가 없어요/)).toBeVisible();
    expect(within(screen.getByRole("list", { name: "탄단지 하루 평균" })).getAllByRole("listitem").map((li) => li.textContent))
      .toEqual(["식이섬유 아는 메뉴가 없어요", "당류 약 25g (1끼 기준)", "나트륨 아는 메뉴가 없어요"]);
  });

  it("탄단지 그래프로 바꾸면 날짜별로 영양소를 쌓고 표도 g으로 바뀐다", async () => {
    fetchMock.mockResolvedValue(summary({
      days: [
        { date: "2026-10-07", kcal: 0, meals: 0, unknownMeals: 0 },
        { date: "2026-10-08", kcal: 600, meals: 2, unknownMeals: 0, slots: slotsOf(0, 300, 300, 0),
          macros: { meals: 2, carbsG: 90, proteinG: 30, fatG: 20 } },
        { date: "2026-10-09", kcal: 750, meals: 2, unknownMeals: 1, slots: slotsOf(0, 750, 0, 0),
          macros: { meals: 1, carbsG: 45, proteinG: 18, fatG: 0 } },
      ],
      macros: { meals: 3, carbsG: 68, proteinG: 24, fatG: 10, fiberMeals: 0, fiberG: null },
    }));
    const user = userEvent.setup();
    const { container } = renderWithProviders(<NutritionSummary />);
    expect(await screen.findByRole("button", { name: "열량" })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "탄단지" }));

    const svg = container.querySelector(".nutrition-chart svg")!;
    expect(svg.querySelectorAll(".nutrition-seg-carbs")).toHaveLength(2);
    // 0g 조각은 그리지 않는다.
    expect(svg.querySelectorAll(".nutrition-seg-fat")).toHaveLength(1);
    expect(svg.querySelectorAll(".nutrition-seg-lunch")).toHaveLength(0);
    expect(within(screen.getByRole("list", { name: "그래프 색: 영양소" })).getAllByRole("listitem").map((li) => li.textContent))
      .toEqual(["탄수화물", "단백질", "지방", "? 모르는 끼니 있음"]);
    fireEvent.mouseEnter(svg.querySelectorAll(".nutrition-chart-hit")[2]);
    expect(container.querySelector(".nutrition-chart-tooltip"))
      .toHaveTextContent("10/9 (금)약 63g탄수화물 45g단백질 18g모름 1끼");

    const rows = within(screen.getByRole("table", { name: /하루 탄단지/ })).getAllByRole("row");
    expect(within(rows[0]).getAllByRole("columnheader").map((th) => th.textContent))
      .toEqual(["날짜", "탄수화물", "단백질", "지방", "모름"]);
    expect(rows[1]).toHaveTextContent("기록 없음");
    expect(within(rows[3]).getAllByRole("cell").map((td) => td.textContent)).toEqual(["45", "18", "0", "1끼"]);
  });

  it("선 그래프는 기록 없는 날에서 끊기고, 표시점에 마우스를 대면 그날 값을 말한다", async () => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<NutritionSummary />);
    await screen.findByText(/하루 평균/);
    await user.click(screen.getByRole("button", { name: "선" }));
    expect(screen.getByRole("button", { name: "선" })).toHaveAttribute("aria-pressed", "true");

    const svg = container.querySelector(".nutrition-chart svg")!;
    expect(svg.querySelectorAll(".nutrition-seg")).toHaveLength(0);
    // 10/7은 기록이 없어 점이 둘이고, 경로는 그 둘만 잇는다(M…L…).
    expect(svg.querySelectorAll(".nutrition-line-marker")).toHaveLength(2);
    expect(svg.querySelector(".nutrition-line-path")!.getAttribute("d")).toMatch(/^M[\d.,]+L[\d.,]+$/);
    fireEvent.mouseEnter(svg.querySelectorAll(".nutrition-chart-hit")[2]);
    expect(container.querySelector(".nutrition-chart-tooltip")).toHaveTextContent("10/9 (금)약 750kcal모름 1끼");
    // 고른 모양은 다음에 열어도 그대로다.
    expect(window.localStorage.getItem("menupick.nutritionChartKind")).toBe("line");
  });

  it("원그래프는 기간 열량을 끼니별 몫(%)으로 나누고, 같은 값을 말하는 끼니 칩은 숨긴다", async () => {
    window.localStorage.setItem("menupick.nutritionChartKind", "donut");
    const { container } = renderWithProviders(<NutritionSummary />);
    await screen.findByText(/하루 평균/);
    expect(screen.getByRole("button", { name: "원" })).toHaveAttribute("aria-pressed", "true");
    expect(container.querySelectorAll(".nutrition-donut-slice")).toHaveLength(2);
    expect(within(screen.getByRole("list", { name: /끼니별 열량 몫/ })).getAllByRole("listitem").map((li) => li.textContent))
      .toEqual(["점심 78% 약 1,050kcal", "저녁 22% 약 300kcal"]);
    expect(screen.queryByRole("list", { name: "끼니별 열량" })).not.toBeInTheDocument();
  });

  it("탄단지 원그래프는 하루 평균 g을 열량 비율로 나눈다", async () => {
    fetchMock.mockResolvedValue(summary({
      days: [{ date: "2026-10-09", kcal: 600, meals: 2, unknownMeals: 0, slots: slotsOf(0, 300, 300, 0),
        macros: { meals: 2, carbsG: 200, proteinG: 60, fatG: 50 } }],
      macros: { meals: 2, carbsG: 200, proteinG: 60, fatG: 50, fiberMeals: 0, fiberG: null },
    }));
    const user = userEvent.setup();
    renderWithProviders(<NutritionSummary />);
    await user.click(await screen.findByRole("button", { name: "원" }));
    await user.click(screen.getByRole("button", { name: "탄단지" }));
    expect(within(screen.getByRole("list", { name: /탄단지 열량 비율/ })).getAllByRole("listitem").map((li) => li.textContent))
      .toEqual(["탄수화물 54% 하루 200g", "단백질 16% 하루 60g", "지방 30% 하루 50g"]);
  });

  it("저장소가 막혀 있어도 막대로 시작하고 모양을 바꿀 수 있다", async () => {
    const get = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("blocked"); });
    const set = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
    const user = userEvent.setup();
    renderWithProviders(<NutritionSummary />);
    await screen.findByText(/하루 평균/);
    expect(screen.getByRole("button", { name: "막대" })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "원" }));
    expect(screen.getByRole("button", { name: "원" })).toHaveAttribute("aria-pressed", "true");
    get.mockRestore();
    set.mockRestore();
  });

  it("탄단지를 아는 날이 없으면 그래프 종류를 고르지 않는다", async () => {
    renderWithProviders(<NutritionSummary />);
    await screen.findByText(/하루 평균/);
    expect(screen.queryByRole("group", { name: "그래프 종류" })).not.toBeInTheDocument();
  });

  it("7일과 30일을 바꿔 본다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NutritionSummary />);
    await screen.findByText(/하루 평균/);
    expect(fetchMock).toHaveBeenLastCalledWith(7);
    expect(screen.getByRole("button", { name: "열량 7일" })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "열량 30일" }));
    expect(fetchMock).toHaveBeenLastCalledWith(30);
    expect(screen.getByRole("button", { name: "열량 30일" })).toHaveAttribute("aria-pressed", "true");
  });

  it("예전 서버처럼 끼니별 몫이 없으면 합계 한 조각으로 그린다", async () => {
    fetchMock.mockResolvedValue(summary({ days: [{ date: "2026-10-09", kcal: 500, meals: 1, unknownMeals: 0 }] }));
    const { container } = renderWithProviders(<NutritionSummary />);
    await screen.findByText(/하루 평균/);
    expect(container.querySelectorAll(".nutrition-seg-total")).toHaveLength(1);
  });

  it("먹은 끼니만 끼니별로 보여 주고, 모르는 끼니가 있으면 메뉴에 열량을 정하러 가게 한다", async () => {
    renderWithProviders(<NutritionSummary />);
    const slots = await screen.findByRole("list", { name: "끼니별 열량" });
    expect(within(slots).getAllByRole("listitem").map((li) => li.textContent))
      .toEqual(["점심 약 1,050kcal", "저녁 약 300kcal"]);
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

describe("niceTicks", () => {
  it.each([
    [0, [0, 500, 1000]], [1000, [0, 500, 1000]], [1010, [0, 500, 1000, 1500]], [2380, [0, 1000, 2000, 3000]],
  ])("%d → %j", (max, ticks) => expect(niceTicks(max)).toEqual(ticks));
});

describe("percentShares", () => {
  it("몇 개든 정수 몫의 합을 100에 맞춘다", () => {
    expect(percentShares([1, 1, 1])).toEqual([34, 33, 33]);
    expect(percentShares([1050, 300])).toEqual([78, 22]);
    expect(percentShares([0, 0])).toBeNull();
  });
});

describe("energyShares", () => {
  it("열량 기준 몫을 정수로 내고 합을 100에 맞춘다", () => {
    expect(energyShares(200, 60, 50)).toEqual([54, 16, 30]);
    expect(energyShares(1, 1, 0)).toEqual([50, 50, 0]);
    const thirds = energyShares(9, 9, 4)!;
    expect(thirds[0] + thirds[1] + thirds[2]).toBe(100);
    expect(energyShares(0, 0, 0)).toBeNull();
  });
});
