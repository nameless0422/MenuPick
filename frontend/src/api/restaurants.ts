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
  kakaoPlaceId: string | null;
  createdAt: string;
  updatedAt: string;
}

// RestaurantRequest.Update — 사용자가 고칠 수 있는 값만 보낸다.
// kakaoPlaceId는 없다: 이름을 고친다고 다른 장소가 되지는 않으므로 서버가 받지 않는다.
export interface RestaurantUpdateRequest {
  name: string;
  address?: string | null;
  phone?: string | null;
  latitude: number;
  longitude: number;
  naverUrl?: string | null;
}

// RestaurantRequest.Create — 여기에만 kakaoPlaceId가 있다. 같은 장소를 이미 저장했는지
// 서버가 이 값으로 판정한다(이름은 겹칠 수 있어 기준이 못 된다).
export interface RestaurantCreateRequest extends RestaurantUpdateRequest {
  kakaoPlaceId?: string | null;
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

/**
 * 식당을 저장한다.
 *
 * 이미 같은 장소를 저장해 뒀으면 서버가 새로 만들지 않고 갖고 있던 식당을 200으로 준다
 * (새로 만들었을 때만 201). 사용자에게 "저장했다"와 "이미 있다"는 다른 사실이라
 * 상태 코드를 그대로 올려보낸다.
 */
export async function createRestaurant(request: RestaurantCreateRequest) {
  const res = await http.post<ApiResponse<RestaurantDetail>>("/api/v1/restaurants", request);
  return { restaurant: res.data.data!, created: res.status === 201 };
}

export async function updateRestaurant(restaurantId: number, request: RestaurantUpdateRequest) {
  const res = await http.put<ApiResponse<RestaurantDetail>>(
    `/api/v1/restaurants/${restaurantId}`,
    request,
  );
  return res.data.data!;
}

export async function deleteRestaurant(restaurantId: number) {
  await http.delete<ApiResponse<null>>(`/api/v1/restaurants/${restaurantId}`);
}
