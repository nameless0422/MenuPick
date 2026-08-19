import { http, type ApiResponse } from "./http";
import type { MenuDetail } from "./menus";

// 백엔드 PickRequest record와 1:1 대응 — 모든 필드는 선택이며,
// 값이 없으면 필드 자체를 생략한다 (record 컴포넌트가 null이면 해당 필터는 미적용).
export interface PickRequest {
  categories?: string[];
  tagIds?: number[];
  excludeTagIds?: number[];
  latitude?: number;
  longitude?: number;
  maxDistance?: number;
}

// PickResponse.RestaurantWithDistance — distance는 요청에 위치가 없으면 null이지만
// 좌표는 항상 온다(픽 결과를 지도에 찍는 데 쓴다).
export interface PickRestaurant {
  id: number;
  name: string;
  address: string | null;
  latitude: number;
  longitude: number;
  distance: number | null;
}

// PickResponse.PickResult — menu는 메뉴 상세(MenuResponse.MenuDetail)와 동일 구조
export interface PickResult {
  historyId: number;
  menu: MenuDetail;
  restaurants: PickRestaurant[];
}

export async function requestPick(request: PickRequest) {
  const res = await http.post<ApiResponse<PickResult>>("/api/v1/pick", request);
  return res.data.data!;
}

// PickResponse.DemoPickResult — 저장되지 않는 시연 결과라 id도 historyId도 없다
export interface DemoPickResult {
  name: string;
  categories: string[];
}

// 미인증으로 호출하는 유일한 API. 토큰이 없어도 200이므로 http 인터셉터의
// 401 재발급 경로를 타지 않는다.
export async function requestDemoPick() {
  const res = await http.get<ApiResponse<DemoPickResult>>("/api/v1/pick/demo");
  return res.data.data!;
}
