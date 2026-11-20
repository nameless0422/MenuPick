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

  it("직접 적을 때 탄단지·식이섬유를 함께 보내고, 탄단지 중 일부만 적으면 막는다", async () => {
    updateMock.mockResolvedValue({ menuId: 3, kcalPerServing: 650, foodReference: null, version: 5,
      carbsG: 80, proteinG: 20.5, fatG: 15, fiberG: null });
    const user = userEvent.setup();
    render();
    await user.click(await screen.findByRole("button", { name: "직접 적기" }));
    await user.type(screen.getByRole("spinbutton", { name: "1인분 열량 (kcal)" }), "650");
    await user.type(screen.getByRole("spinbutton", { name: "탄수화물 (g)" }), "80");
    await user.click(screen.getByRole("button", { name: "열량 저장" }));
    expect(updateMock).not.toHaveBeenCalled();
    expect(screen.getByText("탄수화물·단백질·지방은 함께 적거나 모두 비워 주세요.")).toBeVisible();
    expect(screen.getByRole("spinbutton", { name: "단백질 (g)" })).toHaveAttribute("aria-invalid", "true");

    await user.type(screen.getByRole("spinbutton", { name: "단백질 (g)" }), "20.55");
    await user.type(screen.getByRole("spinbutton", { name: "지방 (g)" }), "15");
    await user.click(screen.getByRole("button", { name: "열량 저장" }));
    expect(updateMock).not.toHaveBeenCalled();
    expect(screen.getByRole("spinbutton", { name: "단백질 (g)" })).toHaveAttribute("aria-invalid", "true");

    await user.clear(screen.getByRole("spinbutton", { name: "단백질 (g)" }));
    await user.type(screen.getByRole("spinbutton", { name: "단백질 (g)" }), "20.5{Enter}");
    expect(updateMock).toHaveBeenCalledWith(3, { kcalPerServing: 650, carbsG: 80, proteinG: 20.5, fatG: 15 });
    expect(await screen.findByText("탄수화물 80g · 단백질 20.5g · 지방 15g")).toBeVisible();
  });

  it("기준 음식 후보에 탄단지를 같이 보여 준다", async () => {
    searchMock.mockResolvedValue([{ ...KIMCHI, fiberG: 4.4, sugarsG: 3.1, sodiumMg: 1890 }]);
    const user = userEvent.setup();
    render();
    await user.click(await screen.findByRole("button", { name: "기준 음식에서 찾기" }));
    expect(await screen.findByRole("button",
      { name: /탄수화물 12.4g · 단백질 14g · 지방 9.6g · 식이섬유 4.4g · 당류 3.1g · 나트륨 1,890mg/ })).toBeVisible();
  });

  it("당류·나트륨은 탄단지 없이도 적을 수 있고, 나트륨은 mg 정수다", async () => {
    updateMock.mockResolvedValue({ menuId: 3, kcalPerServing: 100, foodReference: null, version: 5,
      carbsG: null, proteinG: null, fatG: null, fiberG: null, sugarsG: 24.5, sodiumMg: 15 });
    const user = userEvent.setup();
    render();
    await user.click(await screen.findByRole("button", { name: "직접 적기" }));
    await user.type(screen.getByRole("spinbutton", { name: "1인분 열량 (kcal)" }), "100");
    await user.type(screen.getByRole("spinbutton", { name: "당류 (g)" }), "24.5");
    await user.type(screen.getByRole("spinbutton", { name: "나트륨 (mg)" }), "1.5");
    await user.click(screen.getByRole("button", { name: "열량 저장" }));
    expect(updateMock).not.toHaveBeenCalled();
    expect(screen.getByRole("spinbutton", { name: "나트륨 (mg)" })).toHaveAttribute("aria-invalid", "true");

    await user.clear(screen.getByRole("spinbutton", { name: "나트륨 (mg)" }));
    await user.type(screen.getByRole("spinbutton", { name: "나트륨 (mg)" }), "20001");
    expect(screen.getByRole("spinbutton", { name: "나트륨 (mg)" })).toHaveAttribute("aria-invalid", "true");

    await user.clear(screen.getByRole("spinbutton", { name: "나트륨 (mg)" }));
    await user.type(screen.getByRole("spinbutton", { name: "나트륨 (mg)" }), "15{Enter}");
    expect(updateMock).toHaveBeenCalledWith(3, { kcalPerServing: 100, sugarsG: 24.5, sodiumMg: 15 });
    expect(await screen.findByText("당류 24.5g · 나트륨 15mg")).toBeVisible();
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
