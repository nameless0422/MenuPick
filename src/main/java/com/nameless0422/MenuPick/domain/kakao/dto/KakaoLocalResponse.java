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
