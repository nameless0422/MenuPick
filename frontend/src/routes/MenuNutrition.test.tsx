import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import { fetchMenuNutrition, searchFoodReferences, updateMenuNutrition } from "../api/nutrition";
import MenuNutrition from "./MenuNutrition";

vi.mock("../api/nutrition", () => ({
  fetchMenuNutrition: vi.fn(),
  searchFoodReferences: vi.fn(),
  updateMenuNutrition: vi.fn(),
}));
const fetchMock = vi.mocked(fetchMenuNutrition);
const searchMock = vi.mocked(searchFoodReferences);
const updateMock = vi.mocked(updateMenuNutrition);

const KIMCHI = {
  id: 7, name: "김치찌개", category: "찌개", servingGrams: 400, kcalPerServing: 196,
  carbsG: 12.4, proteinG: 14, fatG: 9.6,
};

beforeEach(() => {
  fetchMock.mockReset().mockResolvedValue({ menuId: 3, kcalPerServing: null, foodReference: null, version: 4 });
  searchMock.mockReset().mockResolvedValue([KIMCHI]);
  updateMock.mockReset();
});

const render = (onVersionChange = vi.fn()) => {
  renderWithProviders(<MenuNutrition menuId={3} menuName="회사 앞 김치찌개" onVersionChange={onVersionChange} />);
  return onVersionChange;
};

describe("메뉴 열량", () => {
  it("모르는 메뉴는 그렇다고 말하고, 메뉴 이름으로 기준 음식을 찾아 고르면 그 값을 쓴다", async () => {
    updateMock.mockResolvedValue({ menuId: 3, kcalPerServing: 196, foodReference: KIMCHI, version: 5 });
    const user = userEvent.setup();
    const onVersionChange = render();
    expect(await screen.findByText(/아직 열량을 모르는 메뉴/)).toBeVisible();

    await user.click(screen.getByRole("button", { name: "기준 음식에서 찾기" }));
    expect(screen.getByRole("searchbox", { name: "기준 음식 이름" })).toHaveValue("회사 앞 김치찌개");
    expect(searchMock).toHaveBeenCalledWith("회사 앞 김치찌개");

    await user.click(await screen.findByRole("button", { name: /김치찌개 · 1인분 약 196kcal/ }));
    expect(updateMock).toHaveBeenCalledWith(3, { foodReferenceId: 7 });
    expect(await screen.findByText("1인분 약 196kcal로 정했어요.")).toBeVisible();
    expect(screen.getByText(/기준: 김치찌개 \(400g\)/)).toBeVisible();
    // 바깥 메뉴 수정 폼이 이 버전으로 저장해야 충돌이 나지 않는다.
    expect(onVersionChange).toHaveBeenCalledWith(5);
  });

  it("직접 적은 값은 1~5,000 정수만 저장한다", async () => {
    updateMock.mockResolvedValue({ menuId: 3, kcalPerServing: 650, foodReference: null, version: 5 });
    const user = userEvent.setup();
    render();
    await user.click(await screen.findByRole("button", { name: "직접 적기" }));
    const input = screen.getByRole("spinbutton", { name: "1인분 열량 (kcal)" });

    await user.type(input, "0");
    await user.click(screen.getByRole("button", { name: "열량 저장" }));
    expect(updateMock).not.toHaveBeenCalled();
    expect(input).toHaveAttribute("aria-invalid", "true");

    await user.clear(input);
    await user.type(input, "650{Enter}");
    expect(updateMock).toHaveBeenCalledWith(3, { kcalPerServing: 650 });
    expect(await screen.findByText(/직접 적은 값/)).toBeVisible();
  });

  it("정해 둔 열량은 지울 수 있다", async () => {
    fetchMock.mockResolvedValue({ menuId: 3, kcalPerServing: 500, foodReference: null, version: 4 });
    updateMock.mockResolvedValue({ menuId: 3, kcalPerServing: null, foodReference: null, version: 5 });
    const user = userEvent.setup();
    render();
    await user.click(await screen.findByRole("button", { name: "열량 지우기" }));
    expect(updateMock).toHaveBeenCalledWith(3, {});
    expect(await screen.findByText("열량을 지웠어요.")).toBeVisible();
  });

  it("검색 결과가 없으면 직접 적으라고 안내한다", async () => {
    searchMock.mockResolvedValue([]);
    const user = userEvent.setup();
    render();
    await user.click(await screen.findByRole("button", { name: "기준 음식에서 찾기" }));
    expect(await screen.findByText(/찾는 음식이 없어요/)).toBeVisible();
  });
});
