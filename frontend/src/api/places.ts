import { http, type ApiResponse } from "./http";

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

export async function searchPlacesByKeyword(query: string) {
  const res = await http.get<ApiResponse<PlaceSearchResult>>(
    "/api/v1/kakao/search/keyword",
    { params: { query } },
  );
  return res.data.data!;
}
