import { http, unwrap, type ApiResponse } from "./http";
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
  /**
   * 최근 이 기간(일) 안에 뽑힌 메뉴를 뺀다. 1..30이며, 생략하면 빼지 않는다.
   *
   * 기준은 "먹은 것"이 아니라 뽑힌 것이다 — 방문 처리는 따로 눌러야 하는 선택이라
   * 안 눌린 기록이 대부분이고, 그걸 기준으로 삼으면 이 조건이 거의 아무것도 걸러내지 않는다.
   */
  excludeRecentDays?: number;
  /**
   * 1인분 열량 상한(kcal). 100..5000이며, 생략하면 열량으로 거르지 않는다.
   * 열량을 모르는 메뉴는 빠진다 — "가볍게"를 골랐는데 열량 모르는 메뉴가 나오면 조건이 무의미하다.
   */
  maxKcal?: number;
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
  reasons?: string[];
  /** 뽑힌 메뉴의 1인분 열량. 모르면 null, 구버전 서버면 필드가 없다. */
  kcalPerServing?: number | null;
}

export async function requestPick(request: PickRequest) {
  const res = await http.post<ApiResponse<PickResult>>("/api/v1/pick", request);
  return unwrap(res);
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
  return unwrap(res);
}
