import { http, unwrap, type ApiResponse } from "./http";
import { fetchMenuRestaurants as fetchMenuRestaurantList } from "./menuRestaurants";

// 백엔드 PickService가 실제로 기록하는 filterType 값 (History.java / PickService.java 참고).
// CATEGORY: 카테고리명 그대로, TAG_INCLUDE/TAG_EXCLUDE: 태그 "이름"(조회 실패 시에만 ID 문자열
// 폴백 — PickService.resolveTagNames), MAX_DISTANCE: 미터(m) 값.
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
  return unwrap(res);
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
// 같은 백엔드 DTO를 두 곳에서 각자 선언하면 nullability가 갈린다(실제로 갈려 있었다).
// 타입 정의는 menuRestaurants.ts 하나로 두고 여기서는 다시 내보내기만 한다.
export type { MenuRestaurantDetail as MenuRestaurant } from "./menuRestaurants";

// 이 화면은 목록 배열만 쓰므로 래퍼 응답을 벗겨서 돌려준다.
export async function fetchMenuRestaurants(menuId: number) {
  const { menuRestaurants } = await fetchMenuRestaurantList(menuId);
  return menuRestaurants;
}
