import { http, unwrap, type ApiResponse } from "./http";
import { fetchMenuRestaurants as fetchMenuRestaurantList } from "./menuRestaurants";
import type { KakaoPlace } from "./places";

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
  /** 픽 결과에서 누른 수락/거절. 누르지 않았으면 null. */
  recommendationFeedback: RecommendationFeedback | null;
  filterConditions: HistoryFilterCondition[];
}

export interface HistoryListResponse {
  histories: HistorySummary[];
  nextCursor: number | null;
  hasNext: boolean;
}

export interface HistoryCalendarEntry { id: number; menuName: string | null; restaurantName: string | null; visitedAt: string; }
export interface HistoryCalendarResponse { month: string; entries: HistoryCalendarEntry[]; truncated: boolean; }

const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;
const LOCAL_DATE_TIME_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isStrictLocalDateTime(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const match = LOCAL_DATE_TIME_PATTERN.exec(value);
  if (!match) return false;
  const [year, month, day] = match.slice(1, 4).map(Number);
  const check = new Date(Date.UTC(year, month - 1, day));
  return check.getUTCFullYear() === year && check.getUTCMonth() === month - 1 && check.getUTCDate() === day;
}

export function validateHistoryCalendar(value: unknown, requestedMonth: string): HistoryCalendarResponse {
  if (!MONTH_PATTERN.test(requestedMonth) || !isRecord(value)) throw new Error("방문 기록 달력 응답 형식이 올바르지 않습니다.");
  const { month, entries, truncated } = value;
  if (month !== requestedMonth || !Array.isArray(entries) || entries.length > 500 || typeof truncated !== "boolean") {
    throw new Error("방문 기록 달력 응답 형식이 올바르지 않습니다.");
  }
  for (const entry of entries) {
    if (!isRecord(entry) || !Number.isSafeInteger(entry.id) || Number(entry.id) <= 0
      || !(typeof entry.menuName === "string" || entry.menuName === null)
      || !(typeof entry.restaurantName === "string" || entry.restaurantName === null)
      || !isStrictLocalDateTime(entry.visitedAt) || !entry.visitedAt.startsWith(`${requestedMonth}-`)) {
      throw new Error("방문 기록 달력 응답 형식이 올바르지 않습니다.");
    }
  }
  return value as unknown as HistoryCalendarResponse;
}

export async function fetchHistoryCalendar(month: string, signal?: AbortSignal) {
  const res = await http.get<ApiResponse<unknown>>("/api/v1/history/calendar", { params: { month }, signal });
  return validateHistoryCalendar(unwrap(res), month);
}

export async function fetchHistories(cursor?: number, days?: number, size = 20, visited?: boolean) {
  const res = await http.get<ApiResponse<HistoryListResponse>>("/api/v1/history", {
    params: { cursor, days, size, visited },
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

export async function unmarkVisited(historyId: number) {
  await http.delete<ApiResponse<null>>(`/api/v1/history/${historyId}/visit`);
}

export async function deleteHistory(historyId: number) {
  await http.delete<ApiResponse<null>>(`/api/v1/history/${historyId}`);
}

export interface LabelCount {
  label: string;
  /** **횟수**다. 집단 통계(trends)의 userCount가 사람 수인 것과 다르다 — 여기는 내 기록이다. */
  count: number;
}

export interface ForgottenMenu {
  menuId: number;
  name: string;
  /** null이면 한 번도 뽑힌 적이 없다. 시각을 지어내지 않는다. */
  lastPickedAt: string | null;
}

export interface EatingSummary {
  periodDays: number;
  picks: number;
  /** 방문 처리했거나 "이걸로 먹을래요"를 누른 수. 비율이 아니라 건수로 온다. */
  eaten: number;
  categories: LabelCount[];
  menus: LabelCount[];
  /** 오래 안 뽑힌 내 메뉴. 이것만 집계 기간과 무관하게 전 기간을 본다. */
  forgottenMenus: ForgottenMenu[];
}

export async function fetchEatingSummary(days?: number) {
  const res = await http.get<ApiResponse<EatingSummary>>("/api/v1/history/summary", {
    params: days != null ? { days } : undefined,
  });
  return unwrap(res);
}

export type RecommendationFeedback = "ACCEPTED" | "REJECTED";

export interface PlaceChoiceResult {
  restaurantId: number;
  restaurantName: string;
  /** 이번에 처음 저장한 식당인가. false면 이미 저장해 둔 식당을 썼다. */
  restaurantCreated: boolean;
  /** 이번에 메뉴와 새로 연결했는가. false면 이미 연결돼 있었다. */
  linkCreated: boolean;
}

/**
 * 뽑은 메뉴를 먹으러 갈 식당을 주변 검색 결과에서 고른다. 서버가 식당 저장·메뉴 연결·픽 기록을
 * 한 번에 처리한다(HistoryPlaceService). 방문 처리는 하지 않는다 — 아직 가기 전이다.
 */
export async function choosePickPlace(historyId: number, place: KakaoPlace) {
  const res = await http.post<ApiResponse<PlaceChoiceResult>>(`/api/v1/history/${historyId}/place`, {
    name: place.place_name,
    address: place.road_address_name || place.address_name || null,
    phone: place.phone || null,
    // 카카오 좌표는 x=경도, y=위도 (문자열)
    latitude: Number(place.y),
    longitude: Number(place.x),
    naverUrl: place.place_url || null,
    kakaoPlaceId: place.id,
  });
  return unwrap(res);
}

export async function recordPickFeedback(historyId: number, feedback: RecommendationFeedback) {
  await http.patch<ApiResponse<null>>(`/api/v1/history/${historyId}/feedback`, { feedback });
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
