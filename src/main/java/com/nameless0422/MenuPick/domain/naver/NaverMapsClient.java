package com.nameless0422.MenuPick.domain.naver;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.naver.dto.NaverMapsResponse;
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
import java.util.StringJoiner;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class NaverMapsClient {

    /**
     * 캐시 키에 쓰는 좌표 정밀도(소수점 자릿수). 3자리 ≈ 100m 격자.
     * GPS가 미세하게 흔들릴 때마다 새 키가 생겨 캐시 카디널리티가 폭발하는 것을 막는다.
     * 반올림 좌표는 <b>캐시 키 전용</b>이고 업스트림에는 원본 좌표를 그대로 보낸다.
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

    private final NaverMapsProperties naverMapsProperties;
    private final WebClient webClient;

    public NaverMapsClient(NaverMapsProperties naverMapsProperties, WebClient webClient) {
        this.naverMapsProperties = naverMapsProperties;
        // 공용 빈(common/security/SecurityConfig)을 그대로 쓰면 OAuth 호출까지 타임아웃이 바뀌므로,
        // 여기서만 커넥터를 갈아끼운 파생 클라이언트를 만든다.
        this.webClient = webClient.mutate()
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                                .responseTimeout(RESPONSE_TIMEOUT)))
                .build();
    }

    @Cacheable(value = "naverGeocode",
            key = "T(com.nameless0422.MenuPick.domain.naver.NaverMapsClient)"
                    + ".geocodeCacheKey(#query, #page, #count)",
            // sync=true: 같은 키의 캐시가 만료되는 순간 동시 요청이 전부 업스트림으로 몰려나가는
            // 캐시 스탬피드를 막는다(같은 키는 한 스레드만 로딩, 나머지는 대기).
            // 주의) sync=true는 unless와 함께 쓸 수 없다(기동 시 IllegalStateException).
            //       null 결과를 이미 예외로 바꾸므로 unless는 어차피 죽은 코드였다.
            // RedisConfig의 fail-open(CacheErrorHandler)은 유지된다 — Spring 6.2+의 동기 경로도
            // AbstractCacheInvoker.doGet(cache, key, loader)를 거쳐 캐시 오류를 미스로 강등한다.
            sync = true)
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
                    .retryWhen(retrySpec("Geocode"))
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
            // sync=true 근거·주의사항은 geocode 주석 참고
            sync = true)
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
                    .retryWhen(retrySpec("ReverseGeocode"))
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
                .filter(NaverMapsClient::isRetryable)
                .doBeforeRetry(signal -> log.warn("네이버 {} API 재시도: cause={}",
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
