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
  /** 원본에 없는 음식이 많다. 예전 서버는 주지 않는다. */
  fiberG?: number | null;
  sugarsG?: number | null;
  sodiumMg?: number | null;
  /** `servingGrams`의 단위 — "g" 또는 "ml"(D-090). 예전 서버는 주지 않는다(g로 본다). */
  servingUnit?: string | null;
  /** 값의 출처 구분: 외식·가정식·프랜차이즈·급식. 같은 이름이 여럿일 때 고르는 근거다. */
  origin?: string | null;
}

/**
 * 1인분 탄수화물·단백질·지방·식이섬유(g, D-084)와 당류(g)·나트륨(mg, D-085). 모르면 null.
 * 탄단지는 셋이 함께 있거나 함께 없다.
 */
export interface Macros {
  carbsG: number | null;
  proteinG: number | null;
  fatG: number | null;
  fiberG: number | null;
  sugarsG: number | null;
  sodiumMg: number | null;
}

/**
 * 메뉴의 열량 설정. `foodReference`가 null이고 `kcalPerServing`이 있으면 직접 적은 값이다.
 * `version`은 메뉴의 낙관적 락 버전이다 — 열량을 바꾸면 메뉴 버전도 오르므로, 열려 있는 메뉴
 * 수정 폼은 이 값으로 다음 저장을 보내야 한다.
 */
export interface MenuNutrition extends Partial<Macros> {
  menuId: number;
  kcalPerServing: number | null;
  foodReference: FoodReference | null;
  version: number;
}

export interface SlotNutrition { slot: MealSlot; kcal: number; meals: number; }
/** 하루 탄단지 합계(g, 정수). `meals`는 그날 탄단지를 아는 끼니 수이고, 0이면 셋 다 null. */
export interface DayMacros { meals: number; carbsG: number | null; proteinG: number | null; fatG: number | null; }

/**
 * 하루치. `slots`는 그날 열량의 끼니별 몫이다(네 끼니, 순서 고정). `macros`는 그날 탄단지 합계다.
 * 둘 다 예전 서버는 주지 않는다.
 */
export interface DayNutrition {
  date: string; kcal: number; meals: number; unknownMeals: number; slots?: SlotNutrition[]; macros?: DayMacros;
}

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
  /** 예전 서버는 주지 않는다. */
  macros?: MacroSummary;
  /** 사용자가 정한 하루 열량 기준선(kcal). 없으면 null, 예전 서버는 주지 않는다(D-093). */
  dailyKcalReference?: number | null;
}

/** 하루 열량 기준선 설정. null이면 지운다. 범위(500~6,000)는 오타 막기이지 판정이 아니다. */
export interface KcalReference {
  dailyKcal: number | null;
}

/**
 * 탄단지·식이섬유 하루 평균(g, 정수). 그 영양소를 아는 끼니가 있던 날로만 나눈다.
 * `meals`는 탄단지를 아는 끼니 수, `fiberMeals`는 식이섬유를 아는 끼니 수다.
 */
export interface MacroSummary {
  meals: number;
  carbsG: number | null;
  proteinG: number | null;
  fatG: number | null;
  fiberMeals: number;
  fiberG: number | null;
  /** 당류(g)·나트륨(mg). 식이섬유처럼 하나씩 따로 센다. 예전 서버는 주지 않는다. */
  sugarsMeals?: number;
  sugarsG?: number | null;
  sodiumMeals?: number;
  sodiumMg?: number | null;
}

export async function searchFoodReferences(q: string, size = 10) {
  const res = await http.get<ApiResponse<FoodReference[]>>("/api/v1/food-references", { params: { q, size } });
  return unwrap(res);
}

export async function fetchMenuNutrition(menuId: number) {
  const res = await http.get<ApiResponse<MenuNutrition>>(`/api/v1/menus/${menuId}/nutrition`);
  return unwrap(res);
}

/**
 * 기준 음식을 고르거나(`foodReferenceId`), 직접 적거나(`kcalPerServing`, 탄단지·식이섬유는 선택), 모두 비워 지운다.
 * 탄단지는 셋을 함께 보내거나 모두 빼야 한다.
 */
export type MenuNutritionUpdate = { foodReferenceId?: number; kcalPerServing?: number } & Partial<Macros>;

export async function updateMenuNutrition(menuId: number, body: MenuNutritionUpdate) {
  const res = await http.put<ApiResponse<MenuNutrition>>(`/api/v1/menus/${menuId}/nutrition`, body);
  return unwrap(res);
}

export async function fetchKcalReference() {
  const res = await http.get<ApiResponse<KcalReference>>("/api/v1/nutrition/reference");
  return unwrap(res);
}

export async function updateKcalReference(dailyKcal: number | null) {
  const res = await http.put<ApiResponse<KcalReference>>("/api/v1/nutrition/reference", { dailyKcal });
  return unwrap(res);
}

export async function fetchNutritionSummary(days?: number) {
  const res = await http.get<ApiResponse<NutritionSummary>>("/api/v1/history/nutrition", {
    params: days != null ? { days } : undefined,
  });
  return unwrap(res);
}
