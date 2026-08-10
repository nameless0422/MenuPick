package com.nameless0422.MenuPick.domain.naver;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.naver.dto.NaverMapsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.StringJoiner;

@Slf4j
@Component
@RequiredArgsConstructor
public class NaverMapsClient {

    /**
     * 캐시 키에 쓰는 좌표 정밀도(소수점 자릿수). 3자리 ≈ 100m 격자.
     * GPS가 미세하게 흔들릴 때마다 새 키가 생겨 캐시 카디널리티가 폭발하는 것을 막는다.
     * 반올림 좌표는 <b>캐시 키 전용</b>이고 업스트림에는 원본 좌표를 그대로 보낸다.
     */
    private static final int CACHE_COORD_SCALE = 3;

    private final NaverMapsProperties naverMapsProperties;
    private final WebClient webClient;

    @Cacheable(value = "naverGeocode",
            key = "T(com.nameless0422.MenuPick.domain.naver.NaverMapsClient)"
                    + ".geocodeCacheKey(#query, #page, #count)",
            unless = "#result == null")
    public NaverMapsResponse.GeocodeResult geocode(String query, Integer page, Integer count) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(naverMapsProperties.geocodeBaseUrl())
                .queryParam("query", normalizeQuery(query));
        if (page != null) builder.queryParam("page", page);
        if (count != null) builder.queryParam("count", count);

        URI uri = builder.build().encode().toUri();

        NaverMapsResponse.GeocodeResult result;
        try {
            result = webClient.get()
                    .uri(uri)
                    .header("x-ncp-apigw-api-key-id", naverMapsProperties.clientId())
                    .header("x-ncp-apigw-api-key", naverMapsProperties.clientSecret())
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(NaverMapsResponse.GeocodeResult.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("네이버 Geocode API 응답 오류: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw toBusinessException(e);
        } catch (Exception e) {
            log.error("네이버 Geocode API 호출 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.NAVER_MAPS_API_ERROR);
        }

        if (result == null) {
            log.error("네이버 Geocode API가 빈 응답 본문을 반환했습니다");
            throw new BusinessException(ErrorCode.NAVER_MAPS_API_ERROR);
        }
        return result;
    }

    @Cacheable(value = "naverReverseGeocode",
            key = "T(com.nameless0422.MenuPick.domain.naver.NaverMapsClient)"
                    + ".reverseGeocodeCacheKey(#coords, #orders)",
            unless = "#result == null")
    public NaverMapsResponse.ReverseGeocodeResult reverseGeocode(String coords, String orders) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(naverMapsProperties.reverseGeocodeBaseUrl())
                .queryParam("coords", coords)
                .queryParam("output", "json")
                .queryParam("sourcecrs", "EPSG:4326");
        if (orders != null) builder.queryParam("orders", orders);

        URI uri = builder.build().encode().toUri();

        NaverMapsResponse.ReverseGeocodeResult result;
        try {
            result = webClient.get()
                    .uri(uri)
                    .header("x-ncp-apigw-api-key-id", naverMapsProperties.clientId())
                    .header("x-ncp-apigw-api-key", naverMapsProperties.clientSecret())
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(NaverMapsResponse.ReverseGeocodeResult.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("네이버 ReverseGeocode API 응답 오류: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw toBusinessException(e);
        } catch (Exception e) {
            log.error("네이버 ReverseGeocode API 호출 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.NAVER_MAPS_API_ERROR);
        }

        if (result == null) {
            log.error("네이버 ReverseGeocode API가 빈 응답 본문을 반환했습니다");
            throw new BusinessException(ErrorCode.NAVER_MAPS_API_ERROR);
        }
        return result;
    }

    /**
     * 업스트림 400은 사용자 파라미터 문제이므로 400으로 되돌려준다.
     * 401/403/429/5xx는 우리 쪽 자격증명·쿼터·업스트림 장애이므로 502를 유지한다.
     */
    private BusinessException toBusinessException(WebClientResponseException e) {
        if (e.getStatusCode().value() == 400) {
            return new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return new BusinessException(ErrorCode.NAVER_MAPS_API_ERROR);
    }

    // --- 캐시 키 생성 (SpEL에서 호출, 테스트에서 직접 검증 가능) ---

    public static String geocodeCacheKey(String query, Integer page, Integer count) {
        return normalizeQuery(query) + ':' + page + ':' + count;
    }

    public static String reverseGeocodeCacheKey(String coords, String orders) {
        return normalizeCoords(coords) + ':' + orders;
    }

    /** 앞뒤 공백만 다른 질의가 별도 캐시 엔트리를 차지하지 않도록 정리한다. */
    static String normalizeQuery(String query) {
        return query == null ? null : query.trim();
    }

    /** "x,y" 형태의 좌표 문자열을 각 성분별로 소수점 3자리로 반올림한다. */
    static String normalizeCoords(String coords) {
        if (coords == null) {
            return null;
        }
        String trimmed = coords.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        StringJoiner joiner = new StringJoiner(",");
        for (String part : trimmed.split(",")) {
            joiner.add(normalizeCoord(part));
        }
        return joiner.toString();
    }

    static String normalizeCoord(String coord) {
        String trimmed = coord.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        try {
            return new BigDecimal(trimmed)
                    .setScale(CACHE_COORD_SCALE, RoundingMode.HALF_UP)
                    .toPlainString();
        } catch (NumberFormatException e) {
            return trimmed;
        }
    }
}
