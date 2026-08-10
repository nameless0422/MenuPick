import { http, type ApiResponse } from "./http";

// GET /api/v1/restaurants 목록 응답 (RestaurantResponse.RestaurantSummary — 페이지네이션 없음)
export interface RestaurantSummary {
  id: number;
  name: string;
  address: string | null;
}

// RestaurantResponse.RestaurantDetail
export interface RestaurantDetail {
  id: number;
  name: string;
  address: string | null;
  phone: string | null;
  latitude: number;
  longitude: number;
  naverUrl: string | null;
  naverPlaceId: string | null;
  createdAt: string;
  updatedAt: string;
}

// RestaurantRequest.Create / Update — 필드가 동일하다 (name/latitude/longitude 필수)
export interface RestaurantSaveRequest {
  name: string;
  address?: string | null;
  phone?: string | null;
  latitude: number;
  longitude: number;
  naverUrl?: string | null;
  naverPlaceId?: string | null;
}

export async function fetchRestaurants() {
  const res = await http.get<ApiResponse<RestaurantSummary[]>>("/api/v1/restaurants");
  return res.data.data!;
}

export async function fetchRestaurant(restaurantId: number) {
  const res = await http.get<ApiResponse<RestaurantDetail>>(
    `/api/v1/restaurants/${restaurantId}`,
  );
  return res.data.data!;
}

export async function createRestaurant(request: RestaurantSaveRequest) {
  const res = await http.post<ApiResponse<RestaurantDetail>>("/api/v1/restaurants", request);
  return res.data.data!;
}

export async function updateRestaurant(restaurantId: number, request: RestaurantSaveRequest) {
  const res = await http.put<ApiResponse<RestaurantDetail>>(
    `/api/v1/restaurants/${restaurantId}`,
    request,
  );
  return res.data.data!;
}

export async function deleteRestaurant(restaurantId: number) {
  await http.delete<ApiResponse<null>>(`/api/v1/restaurants/${restaurantId}`);
}
