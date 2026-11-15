import { http, unwrap, type ApiResponse } from "./http";
import type { MealSlot } from "./history";

/** 기준 음식(식약처 음식 DB). 값은 전부 1인분 기준이다. */
export interface FoodReference {
  id: number;
  name: string;
  category: string | null;
  servingGrams: number | null;
  kcalPerServing: number;
  carbsG: number | null;
  proteinG: number | null;
  fatG: number | null;
}

/**
 * 메뉴의 열량 설정. `foodReference`가 null이고 `kcalPerServing`이 있으면 직접 적은 값이다.
 * `version`은 메뉴의 낙관적 락 버전이다 — 열량을 바꾸면 메뉴 버전도 오르므로, 열려 있는 메뉴
 * 수정 폼은 이 값으로 다음 저장을 보내야 한다.
 */
export interface MenuNutrition {
  menuId: number;
  kcalPerServing: number | null;
  foodReference: FoodReference | null;
  version: number;
}

export interface DayNutrition { date: string; kcal: number; meals: number; unknownMeals: number; }
export interface SlotNutrition { slot: MealSlot; kcal: number; meals: number; }

/** 기간 열량 요약. 전부 추정치다. `averageKcal`은 열량을 아는 끼니가 있던 날로만 나눈 값이고, 없으면 null. */
export interface NutritionSummary {
  periodDays: number;
  days: DayNutrition[];
  slots: SlotNutrition[];
  totalKcal: number;
  knownMeals: number;
  unknownMeals: number;
  averageKcal: number | null;
  truncated: boolean;
}

export async function searchFoodReferences(q: string, size = 10) {
  const res = await http.get<ApiResponse<FoodReference[]>>("/api/v1/food-references", { params: { q, size } });
  return unwrap(res);
}

export async function fetchMenuNutrition(menuId: number) {
  const res = await http.get<ApiResponse<MenuNutrition>>(`/api/v1/menus/${menuId}/nutrition`);
  return unwrap(res);
}

/** 기준 음식을 고르거나(`foodReferenceId`), 직접 적거나(`kcalPerServing`), 둘 다 비워 지운다. */
export async function updateMenuNutrition(menuId: number, body: { foodReferenceId?: number; kcalPerServing?: number }) {
  const res = await http.put<ApiResponse<MenuNutrition>>(`/api/v1/menus/${menuId}/nutrition`, body);
  return unwrap(res);
}

export async function fetchNutritionSummary(days?: number) {
  const res = await http.get<ApiResponse<NutritionSummary>>("/api/v1/history/nutrition", {
    params: days != null ? { days } : undefined,
  });
  return unwrap(res);
}
