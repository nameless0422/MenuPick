package com.nameless0422.MenuPick.domain.kakao;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.kakao.dto.KakaoLocalResponse;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoLocalClient {

    /**
     * 캐시 키에 쓰는 좌표 정밀도(소수점 자릿수). 3자리 ≈ 100m 격자.
     * 사용자가 미세하게 움직일 때마다 새 키가 생겨 캐시가 무한히 부풀지 않도록 묶어준다.
     * 반올림 좌표는 <b>캐시 키 전용</b>이며, 업스트림에는 원본 좌표를 그대로 보낸다.
     */
    private static final int CACHE_COORD_SCALE = 3;

    private final KakaoLocalProperties kakaoLocalProperties;
    private final WebClient webClient;

    @Cacheable(value = "kakaoKeywordSearch",
            key = "T(com.nameless0422.MenuPick.domain.kakao.KakaoLocalClient)"
                    + ".keywordCacheKey(#query, #categoryGroupCode, #x, #y, #radius, #page, #size, #sort)",
            unless = "#result == null")
    public KakaoLocalResponse.PlaceSearchResult searchByKeyword(
            String query, String categoryGroupCode,
            String x, String y, Integer radius,
            Integer page, Integer size, String sort) {

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(kakaoLocalProperties.keywordSearchBaseUrl())
                .queryParam("query", normalizeQuery(query));
        if (categoryGroupCode != null) builder.queryParam("category_group_code", categoryGroupCode);
        if (x != null) builder.queryParam("x", x);
        if (y != null) builder.queryParam("y", y);
        if (radius != null) builder.queryParam("radius", radius);
        if (page != null) builder.queryParam("page", page);
        if (size != null) builder.queryParam("size", size);
        if (sort != null) builder.queryParam("sort", sort);

        return execute(builder.build().encode().toUri(), "키워드 검색");
    }

    @Cacheable(value = "kakaoCategorySearch",
            key = "T(com.nameless0422.MenuPick.domain.kakao.KakaoLocalClient)"
                    + ".categoryCacheKey(#categoryGroupCode, #x, #y, #radius, #page, #size, #sort)",
            unless = "#result == null")
    public KakaoLocalResponse.PlaceSearchResult searchByCategory(
            String categoryGroupCode, String x, String y, Integer radius,
            Integer page, Integer size, String sort) {

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(kakaoLocalProperties.categorySearchBaseUrl())
                .queryParam("category_group_code", categoryGroupCode)
                .queryParam("x", x)
                .queryParam("y", y);
        if (radius != null) builder.queryParam("radius", radius);
        if (page != null) builder.queryParam("page", page);
        if (size != null) builder.queryParam("size", size);
        if (sort != null) builder.queryParam("sort", sort);

        return execute(builder.build().encode().toUri(), "카테고리 검색");
    }

    /**
     * 공통 호출/오류 처리.
     *
     * <p>URI는 {@link UriComponentsBuilder}로 미리 만들어 {@code uri(URI)}로 넘긴다 —
     * 문자열 템플릿 오버로드를 쓰면 사용자 입력에 들어 있는 {@code {}}가 URI 변수로 해석되어
     * 템플릿 인젝션이 가능해진다.
     */
    private KakaoLocalResponse.PlaceSearchResult execute(URI uri, String operation) {
        KakaoLocalResponse.PlaceSearchResult result;
        try {
            result = webClient.get()
                    .uri(uri)
                    .header("Authorization", "KakaoAK " + kakaoLocalProperties.restApiKey())
                    .retrieve()
                    .bodyToMono(KakaoLocalResponse.PlaceSearchResult.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("카카오 로컬 {} API 응답 오류: status={}, body={}",
                    operation, e.getStatusCode(), e.getResponseBodyAsString(), e);
            // 업스트림 400은 우리가 만든 장애가 아니라 사용자 파라미터 문제 — 502로 감추지 않는다.
            if (e.getStatusCode().value() == 400) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            throw new BusinessException(ErrorCode.KAKAO_LOCAL_API_ERROR);
        } catch (Exception e) {
            log.error("카카오 로컬 {} API 호출 실패: {}", operation, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KAKAO_LOCAL_API_ERROR);
        }

        // 2xx인데 본문이 비어 block()이 null을 반환하는 경우.
        // 그대로 두면 캐시 AOP(disableCachingNullValues)에서 터져 500이 나가므로 여기서 502로 정리한다.
        if (result == null) {
            log.error("카카오 로컬 {} API가 빈 응답 본문을 반환했습니다", operation);
            throw new BusinessException(ErrorCode.KAKAO_LOCAL_API_ERROR);
        }
        return result;
    }

    // --- 캐시 키 생성 (SpEL에서 호출, 테스트에서 직접 검증 가능) ---

    public static String keywordCacheKey(String query, String categoryGroupCode,
                                         String x, String y, Integer radius,
                                         Integer page, Integer size, String sort) {
        return normalizeQuery(query) + ':' + categoryGroupCode + ':'
                + normalizeCoord(x) + ':' + normalizeCoord(y) + ':' + radius + ':'
                + page + ':' + size + ':' + sort;
    }

    public static String categoryCacheKey(String categoryGroupCode, String x, String y,
                                          Integer radius, Integer page, Integer size, String sort) {
        return categoryGroupCode + ':' + normalizeCoord(x) + ':' + normalizeCoord(y) + ':'
                + radius + ':' + page + ':' + size + ':' + sort;
    }

    /** 앞뒤 공백만 다른 질의가 별도 캐시 엔트리를 차지하지 않도록 정리한다. */
    static String normalizeQuery(String query) {
        return query == null ? null : query.trim();
    }

    /** 좌표를 소수점 {@value #CACHE_COORD_SCALE}자리로 반올림. 숫자가 아니면 원본(trim)을 쓴다. */
    static String normalizeCoord(String coord) {
        if (coord == null) {
            return null;
        }
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
