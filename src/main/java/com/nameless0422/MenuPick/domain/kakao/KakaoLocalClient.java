package com.nameless0422.MenuPick.domain.kakao;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.kakao.dto.KakaoLocalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
@RequiredArgsConstructor
public class KakaoLocalClient {

    private final KakaoLocalProperties kakaoLocalProperties;
    private final WebClient webClient;

    @Cacheable(value = "kakaoKeywordSearch",
            key = "#query + ':' + #categoryGroupCode + ':' + #x + ':' + #y + ':' + #radius + ':' + #page + ':' + #size + ':' + #sort")
    public KakaoLocalResponse.PlaceSearchResult searchByKeyword(
            String query, String categoryGroupCode,
            String x, String y, Integer radius,
            Integer page, Integer size, String sort) {
        try {
            return webClient.get()
                    .uri(kakaoLocalProperties.keywordSearchBaseUrl(), uriBuilder -> {
                        uriBuilder.queryParam("query", query);
                        if (categoryGroupCode != null) uriBuilder.queryParam("category_group_code", categoryGroupCode);
                        if (x != null) uriBuilder.queryParam("x", x);
                        if (y != null) uriBuilder.queryParam("y", y);
                        if (radius != null) uriBuilder.queryParam("radius", radius);
                        if (page != null) uriBuilder.queryParam("page", page);
                        if (size != null) uriBuilder.queryParam("size", size);
                        if (sort != null) uriBuilder.queryParam("sort", sort);
                        return uriBuilder.build();
                    })
                    .header("Authorization", "KakaoAK " + kakaoLocalProperties.restApiKey())
                    .retrieve()
                    .bodyToMono(KakaoLocalResponse.PlaceSearchResult.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw new BusinessException(ErrorCode.KAKAO_LOCAL_API_ERROR);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.KAKAO_LOCAL_API_ERROR);
        }
    }

    @Cacheable(value = "kakaoCategorySearch",
            key = "#categoryGroupCode + ':' + #x + ':' + #y + ':' + #radius + ':' + #page + ':' + #size + ':' + #sort")
    public KakaoLocalResponse.PlaceSearchResult searchByCategory(
            String categoryGroupCode, String x, String y, Integer radius,
            Integer page, Integer size, String sort) {
        try {
            return webClient.get()
                    .uri(kakaoLocalProperties.categorySearchBaseUrl(), uriBuilder -> {
                        uriBuilder.queryParam("category_group_code", categoryGroupCode);
                        uriBuilder.queryParam("x", x);
                        uriBuilder.queryParam("y", y);
                        if (radius != null) uriBuilder.queryParam("radius", radius);
                        if (page != null) uriBuilder.queryParam("page", page);
                        if (size != null) uriBuilder.queryParam("size", size);
                        if (sort != null) uriBuilder.queryParam("sort", sort);
                        return uriBuilder.build();
                    })
                    .header("Authorization", "KakaoAK " + kakaoLocalProperties.restApiKey())
                    .retrieve()
                    .bodyToMono(KakaoLocalResponse.PlaceSearchResult.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw new BusinessException(ErrorCode.KAKAO_LOCAL_API_ERROR);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.KAKAO_LOCAL_API_ERROR);
        }
    }
}
