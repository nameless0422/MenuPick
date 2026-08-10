package com.nameless0422.MenuPick.domain.kakao;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.kakao.dto.KakaoLocalResponse;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class KakaoLocalClient {

    /**
     * 캐시 키에 쓰는 좌표 정밀도(소수점 자릿수). 3자리 ≈ 100m 격자.
     * 사용자가 미세하게 움직일 때마다 새 키가 생겨 캐시가 무한히 부풀지 않도록 묶어준다.
     * 반올림 좌표는 <b>캐시 키 전용</b>이며, 업스트림에는 원본 좌표를 그대로 보낸다.
     */
    private static final int CACHE_COORD_SCALE = 3;

    /**
     * 외부 지도 API 전용 타임아웃. 공용 {@code WebClient} 빈의 기본값(connect 5s / response 10s)은
     * 이 경로에 지나치게 관대하다 — 사용자가 화면 앞에서 기다리는 동기 호출이고,
     * {@code block()}으로 톰캣 스레드를 점유하므로 업스트림이 느려지면 스레드 풀이 그대로 마른다.
     * 재시도 1회를 감안한 최악 지연은 대략 {@code 3s + 0.2s + 3s ≈ 6.2s}다.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    /** 재시도 사이 고정 대기. 업스트림이 순간적으로 흔들린 경우를 흡수할 정도만 준다. */
    private static final Duration RETRY_DELAY = Duration.ofMillis(200);

    private final KakaoLocalProperties kakaoLocalProperties;
    private final WebClient webClient;

    public KakaoLocalClient(KakaoLocalProperties kakaoLocalProperties, WebClient webClient) {
        this.kakaoLocalProperties = kakaoLocalProperties;
        // 공용 빈(common/security/SecurityConfig)을 그대로 쓰면 OAuth 호출까지 타임아웃이 바뀌므로,
        // 여기서만 커넥터를 갈아끼운 파생 클라이언트를 만든다.
        this.webClient = webClient.mutate()
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                                .responseTimeout(RESPONSE_TIMEOUT)))
                .build();
    }

    @Cacheable(value = "kakaoKeywordSearch",
            key = "T(com.nameless0422.MenuPick.domain.kakao.KakaoLocalClient)"
                    + ".keywordCacheKey(#query, #categoryGroupCode, #x, #y, #radius, #page, #size, #sort)",
            // sync=true: 인기 좌표의 캐시가 만료되는 순간 같은 키의 요청이 동시에 업스트림으로
            // 몰려나가는 캐시 스탬피드를 막는다(같은 키는 한 스레드만 로딩, 나머지는 대기).
            // 주의) sync=true는 unless와 함께 쓸 수 없다(기동 시 IllegalStateException).
            //       execute()가 null 결과를 이미 예외로 바꾸므로 unless는 어차피 죽은 코드였다.
            // RedisConfig의 fail-open(CacheErrorHandler)은 유지된다 — Spring 6.2+의 동기 경로도
            // AbstractCacheInvoker.doGet(cache, key, loader)를 거쳐 캐시 오류를 미스로 강등한다.
            sync = true)
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
            // sync=true 근거·주의사항은 searchByKeyword 주석 참고
            sync = true)
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
                    .retryWhen(retrySpec(operation))
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

    /**
     * 멱등한 GET에 한정한 1회 재시도.
     *
     * <p>재시도 대상은 <b>일시적 장애</b>뿐이다 — 5xx와 네트워크/타임아웃 오류.
     * 4xx는 같은 요청을 다시 보내도 같은 답이 오므로(그리고 429는 재시도가 상황을 악화시키므로)
     * 재시도하지 않는다.
     *
     * <p>{@code onRetryExhaustedThrow}로 원본 예외를 그대로 올린다. 기본 동작은 원본을
     * {@code RetryExhaustedException}으로 감싸버려서, 호출부의 {@code WebClientResponseException}
     * 분기(400 → INVALID_INPUT)가 통째로 무력화된다.
     */
    private static Retry retrySpec(String operation) {
        return Retry.fixedDelay(1, RETRY_DELAY)
                .filter(KakaoLocalClient::isRetryable)
                .doBeforeRetry(signal -> log.warn("카카오 로컬 {} API 재시도: cause={}",
                        operation, signal.failure().toString()))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private static boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException e) {
            return e.getStatusCode().is5xxServerError();
        }
        // WebClientRequestException: 커넥션 실패·응답 타임아웃(ReadTimeoutException) 등이 여기로 온다
        return throwable instanceof WebClientRequestException
                || throwable instanceof IOException
                || throwable instanceof TimeoutException;
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
