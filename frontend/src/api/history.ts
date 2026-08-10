import { http, type ApiResponse } from "./http";

// 백엔드 PickService가 실제로 기록하는 filterType 값 (History.java / PickService.java 참고).
// CATEGORY: 카테고리명 그대로, TAG_INCLUDE/TAG_EXCLUDE: 태그 ID(문자열), MAX_DISTANCE: 미터(m) 값.
export type HistoryFilterType = "CATEGORY" | "TAG_INCLUDE" | "TAG_EXCLUDE" | "MAX_DISTANCE";

export interface HistoryFilterCondition {
  filterType: HistoryFilterType | string;
  filterValue: string;
}

export interface HistorySummary {
  id: number;
  // 픽 당시 메뉴/식당이 이후 삭제됐으면 null로 내려온다.
  menuName: string | null;
  restaurantName: string | null;
  isVisited: boolean;
  recommendedAt: string;
  visitedAt: string | null;
  filterConditions: HistoryFilterCondition[];
}

export interface HistoryListResponse {
  histories: HistorySummary[];
  nextCursor: number | null;
  hasNext: boolean;
}

export async function fetchHistories(cursor?: number, days?: number, size = 20) {
  const res = await http.get<ApiResponse<HistoryListResponse>>("/api/v1/history", {
    params: { cursor, days, size },
  });
  return res.data.data!;
}

// restaurantId를 함께 보내면 기록된 식당을 실제 방문한 식당으로 덮어쓴다 (HistoryService.markVisited).
export async function markVisited(historyId: number, restaurantId?: number) {
  await http.patch<ApiResponse<null>>(
    `/api/v1/history/${historyId}/visit`,
    restaurantId != null ? { restaurantId } : undefined,
  );
}

export async function deleteHistory(historyId: number) {
  await http.delete<ApiResponse<null>>(`/api/v1/history/${historyId}`);
}

// 메뉴에 연결된 식당 목록 — 방문 처리 시 실제 방문 식당을 고를 수 있는 후보로 사용.
export interface MenuRestaurant {
  menuId: number;
  restaurantId: number;
  restaurantName: string;
  restaurantAddress: string;
  rating: number | null;
  memo: string | null;
  createdAt: string;
  updatedAt: string;
}

export async function fetchMenuRestaurants(menuId: number) {
  const res = await http.get<ApiResponse<{ menuRestaurants: MenuRestaurant[] }>>(
    `/api/v1/menus/${menuId}/restaurants`,
  );
  return res.data.data!.menuRestaurants;
}
