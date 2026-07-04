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

@Slf4j
@Component
@RequiredArgsConstructor
public class NaverMapsClient {

    private final NaverMapsProperties naverMapsProperties;
    private final WebClient webClient;

    @Cacheable(value = "naverGeocode", key = "#query + ':' + #page + ':' + #count")
    public NaverMapsResponse.GeocodeResult geocode(String query, Integer page, Integer count) {
        try {
            return webClient.get()
                    .uri(naverMapsProperties.geocodeBaseUrl(), uriBuilder -> {
                        uriBuilder.queryParam("query", query);
                        if (page != null) uriBuilder.queryParam("page", page);
                        if (count != null) uriBuilder.queryParam("count", count);
                        return uriBuilder.build();
                    })
                    .header("x-ncp-apigw-api-key-id", naverMapsProperties.clientId())
                    .header("x-ncp-apigw-api-key", naverMapsProperties.clientSecret())
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(NaverMapsResponse.GeocodeResult.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("네이버 Geocode API 응답 오류: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new BusinessException(ErrorCode.NAVER_MAPS_API_ERROR);
        } catch (Exception e) {
            log.error("네이버 Geocode API 호출 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.NAVER_MAPS_API_ERROR);
        }
    }

    @Cacheable(value = "naverReverseGeocode", key = "#coords + ':' + #orders")
    public NaverMapsResponse.ReverseGeocodeResult reverseGeocode(String coords, String orders) {
        try {
            return webClient.get()
                    .uri(naverMapsProperties.reverseGeocodeBaseUrl(), uriBuilder -> {
                        uriBuilder.queryParam("coords", coords);
                        uriBuilder.queryParam("output", "json");
                        uriBuilder.queryParam("sourcecrs", "EPSG:4326");
                        if (orders != null) uriBuilder.queryParam("orders", orders);
                        return uriBuilder.build();
                    })
                    .header("x-ncp-apigw-api-key-id", naverMapsProperties.clientId())
                    .header("x-ncp-apigw-api-key", naverMapsProperties.clientSecret())
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(NaverMapsResponse.ReverseGeocodeResult.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("네이버 ReverseGeocode API 응답 오류: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new BusinessException(ErrorCode.NAVER_MAPS_API_ERROR);
        } catch (Exception e) {
            log.error("네이버 ReverseGeocode API 호출 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.NAVER_MAPS_API_ERROR);
        }
    }
}
