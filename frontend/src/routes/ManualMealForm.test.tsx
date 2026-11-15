import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError } from "axios";
import { renderWithProviders } from "../test/renderWithProviders";
import { createManualMeal, mealSlotAt } from "../api/history";
import { fetchMenus } from "../api/menus";
import ManualMealForm from "./ManualMealForm";

vi.mock("../api/history", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/history")>()),
  createManualMeal: vi.fn(),
}));
vi.mock("../api/menus", () => ({ fetchMenus: vi.fn() }));

const createMock = vi.mocked(createManualMeal);
const menusMock = vi.mocked(fetchMenus);
/** KST 2026-10-20 12:30. 브라우저 시간대와 무관해야 하므로 UTC로 고정한다. */
const NOW = Date.parse("2026-10-20T03:30:00Z");
const MENUS = [
  { id: 3, name: "김치찌개", weight: 1, isExcluded: false, categories: [], tags: [] },
  { id: 4, name: "샐러드", weight: 1, isExcluded: false, categories: [], tags: [] },
];

beforeEach(() => {
  vi.spyOn(Date, "now").mockReturnValue(NOW);
  menusMock.mockReset().mockResolvedValue({ menus: MENUS, nextCursor: null, hasNext: false });
  createMock.mockReset().mockResolvedValue({
    id: 9, menuId: 3, menuName: "김치찌개", restaurantName: null, isVisited: true,
    recommendedAt: "2026-10-20T12:30:00", visitedAt: "2026-10-20T12:30:00", recommendationFeedback: null,
    filterConditions: [], memo: null, source: "MANUAL", mealSlot: "LUNCH",
  });
});
afterEach(() => vi.restoreAllMocks());

const open = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByRole("button", { name: "식사 직접 기록" }));
  await screen.findByRole("option", { name: "김치찌개" });
};

describe("식사 직접 기록", () => {
  it("지금 한국 시각과 그 시각의 끼니를 미리 채우고, 메뉴를 고르기 전에는 저장하지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ManualMealForm />);
    await open(user);

    expect(screen.getByRole("searchbox", { name: "메뉴 찾기" })).toHaveFocus();
    expect(screen.getByLabelText("먹은 날짜 (한국 시간)")).toHaveValue("2026-10-20");
    expect(screen.getByLabelText("먹은 시각 (한국 시간)")).toHaveValue("12:30");
    expect(screen.getByRole("radio", { name: "점심" })).toBeChecked();

    await user.click(screen.getByRole("button", { name: "기록하기" }));
    expect(createMock).not.toHaveBeenCalled();
  });

  it("고른 메뉴와 시각으로 저장하고 결과를 알린 뒤 여는 버튼으로 초점을 돌려준다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ManualMealForm />);
    await open(user);

    await user.selectOptions(screen.getByRole("combobox", { name: "먹은 메뉴" }), "3");
    await user.type(screen.getByRole("textbox", { name: "메모 (선택)" }), "  회사 앞 ");
    await user.click(screen.getByRole("button", { name: "기록하기" }));

    expect(createMock).toHaveBeenCalledWith({
      menuId: 3, eatenAt: "2026-10-20T12:30:00", mealSlot: "LUNCH", memo: "회사 앞",
    });
    expect(await screen.findByText("'김치찌개' 식사를 기록했어요.")).toBeVisible();
    expect(screen.queryByRole("form", { name: "식사 직접 기록" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "식사 직접 기록" })).toHaveFocus();
  });

  it("끼니는 직접 고르기 전까지 시각을 따라가고, 고른 뒤에는 시각을 바꿔도 유지한다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ManualMealForm />);
    await open(user);
    const time = screen.getByLabelText("먹은 시각 (한국 시간)");

    fireEvent.change(time, { target: { value: "08:00" } });
    expect(screen.getByRole("radio", { name: "아침" })).toBeChecked();

    await user.click(screen.getByRole("radio", { name: "간식" }));
    fireEvent.change(time, { target: { value: "19:00" } });
    expect(screen.getByRole("radio", { name: "간식" })).toBeChecked();
  });

  it("오늘의 아직 안 온 시각은 막는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ManualMealForm />);
    await open(user);
    await user.selectOptions(screen.getByRole("combobox", { name: "먹은 메뉴" }), "3");
    fireEvent.change(screen.getByLabelText("먹은 시각 (한국 시간)"), { target: { value: "13:00" } });

    expect(screen.getByText("아직 오지 않은 시각이에요.")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "기록하기" }));
    expect(createMock).not.toHaveBeenCalled();

    // 어제 같은 시각이면 된다.
    fireEvent.change(screen.getByLabelText("먹은 날짜 (한국 시간)"), { target: { value: "2026-10-19" } });
    await user.click(screen.getByRole("button", { name: "기록하기" }));
    expect(createMock).toHaveBeenCalledWith(expect.objectContaining({ eatenAt: "2026-10-19T13:00:00" }));
  });

  it("날짜 범위는 최근 365일이다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ManualMealForm />);
    await open(user);
    const date = screen.getByLabelText("먹은 날짜 (한국 시간)");
    expect(date).toHaveAttribute("max", "2026-10-20");
    expect(date).toHaveAttribute("min", "2025-10-20");
  });

  it("서버가 거절하면 이유를 보여 주고 입력은 유지한다", async () => {
    createMock.mockRejectedValue(new AxiosError("한도", "ERR_BAD_REQUEST", undefined, undefined, {
      data: { success: false, errorCode: "MANUAL_MEAL_DAILY_LIMIT_EXCEEDED",
        message: "하루에 직접 남길 수 있는 식사 기록은 최대 10개입니다." },
      status: 409, statusText: "Conflict", headers: {}, config: {} as never,
    }));
    const user = userEvent.setup();
    renderWithProviders(<ManualMealForm />);
    await open(user);
    await user.selectOptions(screen.getByRole("combobox", { name: "먹은 메뉴" }), "4");
    await user.click(screen.getByRole("button", { name: "기록하기" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("최대 10개");
    expect(screen.getByRole("combobox", { name: "먹은 메뉴" })).toHaveValue("4");
  });

  it("검색어로 메뉴를 다시 찾고, 메뉴가 없으면 추가하러 갈 길을 알려 준다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ManualMealForm />);
    await open(user);
    menusMock.mockResolvedValue({ menus: [], nextCursor: null, hasNext: false });
    await user.type(screen.getByRole("searchbox", { name: "메뉴 찾기" }), "없는메뉴{Enter}");
    expect(menusMock).toHaveBeenLastCalledWith(undefined, 100, "없는메뉴");
    expect(await screen.findByText("검색한 메뉴가 없어요.")).toBeVisible();
  });
});

describe("mealSlotAt", () => {
  it.each([
    ["04:59", "SNACK"], ["05:00", "BREAKFAST"], ["10:59", "BREAKFAST"], ["11:00", "LUNCH"],
    ["15:59", "LUNCH"], ["16:00", "DINNER"], ["21:59", "DINNER"], ["22:00", "SNACK"],
  ])("%s → %s (서버 MealSlot.at과 같은 경계)", (time, slot) => {
    expect(mealSlotAt(time)).toBe(slot);
  });
});
