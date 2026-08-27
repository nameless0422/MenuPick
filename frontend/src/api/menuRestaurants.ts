import { http, unwrap, type ApiResponse } from "./http";

// MenuRestaurantResponse.MenuRestaurantDetail — 경로가 /api/v1/menus/{menuId}/restaurants 로 메뉴 중심
export interface MenuRestaurantDetail {
  menuId: number;
  restaurantId: number;
  restaurantName: string;
  restaurantAddress: string | null;
  rating: number | null;
  memo: string | null;
  createdAt: string;
  updatedAt: string;
  /**
   * 낙관적 락 버전. 수정 요청에 **그대로 되돌려 보내야** 한다 — 서버는 이 값으로
   * "내가 이 화면을 그린 뒤 누가 먼저 고쳤는가"를 판정한다. 값을 빼면 400이고,
   * 오래된 값을 보내면 409 CONCURRENT_MODIFICATION이다.
   */
  version: number;
}

export interface MenuRestaurantListResponse {
  menuRestaurants: MenuRestaurantDetail[];
}

export interface MenuRestaurantCreateRequest {
  restaurantId: number;
  rating?: number | null;
  memo?: string | null;
}

export interface MenuRestaurantUpdateRequest {
  rating?: number | null;
  memo?: string | null;
  // 화면을 그릴 때 받은 MenuRestaurantDetail.version을 그대로 싣는다.
  version: number;
}

export async function fetchMenuRestaurants(menuId: number) {
  const res = await http.get<ApiResponse<MenuRestaurantListResponse>>(
    `/api/v1/menus/${menuId}/restaurants`,
  );
  return unwrap(res);
}

export async function createMenuRestaurant(menuId: number, request: MenuRestaurantCreateRequest) {
  const res = await http.post<ApiResponse<MenuRestaurantDetail>>(
    `/api/v1/menus/${menuId}/restaurants`,
    request,
  );
  return unwrap(res);
}

export async function updateMenuRestaurant(
  menuId: number,
  restaurantId: number,
  request: MenuRestaurantUpdateRequest,
) {
  const res = await http.put<ApiResponse<MenuRestaurantDetail>>(
    `/api/v1/menus/${menuId}/restaurants/${restaurantId}`,
    request,
  );
  return unwrap(res);
}

export async function deleteMenuRestaurant(menuId: number, restaurantId: number) {
  await http.delete<ApiResponse<null>>(`/api/v1/menus/${menuId}/restaurants/${restaurantId}`);
}
