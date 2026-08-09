package com.nameless0422.MenuPick.domain.naver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

public class NaverMapsResponse {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeocodeResult(
            String status,
            Meta meta,
            List<Address> addresses
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Meta(int totalCount, int page, int count) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Address(
                String roadAddress,
                String jibunAddress,
                String x,
                String y,
                String distance
        ) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReverseGeocodeResult(
            Status status,
            List<Result> results
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Status(int code, String name, String message) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Result(
                String name,
                Region region,
                Land land
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            public record Region(Area area0, Area area1, Area area2, Area area3, Area area4) {
                @JsonIgnoreProperties(ignoreUnknown = true)
                public record Area(String name, Coords coords) {
                    @JsonIgnoreProperties(ignoreUnknown = true)
                    public record Coords(Center center) {
                        @JsonIgnoreProperties(ignoreUnknown = true)
                        public record Center(String crs, String x, String y) {}
                    }
                }
            }

            @JsonIgnoreProperties(ignoreUnknown = true)
            public record Land(
                    String type,
                    String number1,
                    String number2,
                    Addition addition0,
                    String name
            ) {
                @JsonIgnoreProperties(ignoreUnknown = true)
                public record Addition(String type, String value) {}
            }
        }
    }
}
