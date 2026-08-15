package com.nameless0422.MenuPick.domain.kakao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class KakaoLocalResponse {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlaceSearchResult(
            Meta meta,
            List<Place> documents
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Meta(
                @JsonProperty("total_count") int totalCount,
                @JsonProperty("pageable_count") int pageableCount,
                @JsonProperty("is_end") boolean isEnd
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Place(
                /*
                 * 카카오가 장소마다 부여하는 식별자(숫자 문자열). 같은 장소를 두 번 저장하지
                 * 않기 위한 판정 기준이라 프론트까지 그대로 내려보낸다 — 이름은 다른 가게끼리도
                 * 겹치므로 기준이 될 수 없다. (RestaurantService.createRestaurant)
                 */
                String id,
                @JsonProperty("place_name") String placeName,
                @JsonProperty("address_name") String addressName,
                @JsonProperty("road_address_name") String roadAddressName,
                String x,
                String y,
                String phone,
                @JsonProperty("place_url") String placeUrl,
                @JsonProperty("category_name") String categoryName,
                @JsonProperty("category_group_code") String categoryGroupCode,
                @JsonProperty("category_group_name") String categoryGroupName,
                String distance
        ) {}
    }
}
