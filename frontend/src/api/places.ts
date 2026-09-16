import { http, unwrap, type ApiResponse } from "./http";

// 백엔드가 카카오 로컬 API 응답(KakaoLocalResponse)을 그대로 프록시한다.
// DTO의 @JsonProperty가 직렬화에도 적용되므로 JSON 키는 카카오 원본과 같은 snake_case다.
// 주의: x=경도(longitude), y=위도(latitude)이고 둘 다 문자열이다.
export interface KakaoPlace {
  // 카카오가 장소마다 부여하는 식별자. 같은 장소를 두 번 저장하지 않기 위해
  // 저장 요청에 그대로 실어 보낸다 — 이름은 다른 가게끼리도 겹쳐 기준이 못 된다.
  id: string;
  place_name: string;
  address_name: string | null;
  road_address_name: string | null;
  x: string;
  y: string;
  phone: string | null;
  place_url: string | null;
  category_name: string | null;
  category_group_code: string | null;
  category_group_name: string | null;
  distance: string | null;
}

export interface PlaceSearchResult {
  meta: {
    total_count: number;
    pageable_count: number;
    is_end: boolean;
  };
  documents: KakaoPlace[];
}

/**
 * 현재 위치 주변에서 키워드로 찾는다. 가까운 순이며 결과마다 `distance`(m, 문자열)가 채워진다.
 *
 * 반경은 2km가 기본이다 — 픽 거리 필터의 최대 단계(2000m)와 같고, 걸어서 점심 먹으러 갈
 * 범위를 넘는 결과는 "근처"라고 부르기 어렵다.
 */
export async function searchNearbyPlaces(
  query: string,
  position: { latitude: number; longitude: number },
  radius = 2000,
) {
  const res = await http.get<ApiResponse<PlaceSearchResult>>(
    "/api/v1/kakao/search/keyword",
    {
      params: {
        query,
        // 카카오 규약: x=경도, y=위도.
        x: String(position.longitude),
        y: String(position.latitude),
        radius,
        sort: "distance",
        size: 5,
      },
    },
  );
  return unwrap(res);
}

export async function searchPlacesByKeyword(query: string) {
  const res = await http.get<ApiResponse<PlaceSearchResult>>(
    "/api/v1/kakao/search/keyword",
    { params: { query } },
  );
  return unwrap(res);
}
