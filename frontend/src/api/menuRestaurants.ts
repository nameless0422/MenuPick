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
