package com.nameless0422.MenuPick.domain.naver;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.http.ExternalApiConnector;
import com.nameless0422.MenuPick.domain.naver.dto.NaverMapsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
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
     * Geocode 응답 본문의 정상 status. 문서상 값은 {@code OK} / {@code INVALID_REQUEST} /
     * {@code SYSTEM_ERROR} 셋뿐이고 뒤의 둘은 각각 HTTP 400·500과 함께 오므로 이미 예외 경로로
     * 걸린다. 그럼에도 본문을 확인하는 이유는 사이에 낀 프록시·게이트웨이가 오류 본문을 200으로
     * 감싸 돌려주는 경우를 막기 위해서다 — 그때 예외를 던지지 않으면 깨진 응답이 캐시에 24시간
     * 박힌다({@code @Cacheable}은 예외가 나면 저장하지 않는다).
     */
    private static final String GEOCODE_STATUS_OK = "OK";

    /**
     * ReverseGeocode 응답 본문의 정상 status.code.
     * <pre>
     *   0   ok          정상 처리, 결과 반환
     *   3   no results  정상 처리, 결과 없음  ← 오류가 아니다. 캐시해도 된다
     *   100 invalid request (HTTP 400과 함께 온다)
     *   900 unknown error  (HTTP 500과 함께 온다)
     * </pre>
     */
    private static final Set<Integer> REVERSE_GEOCODE_OK_CODES = Set.of(0, 3);

    /** 재시도 사이 고정 대기. 업스트림이 순간적으로 흔들린 경우를 흡수할 정도만 준다. */
    private static final Duration RETRY_DELAY = Duration.ofMillis(200);

    private final NaverMapsProperties naverMapsProperties;
    private final WebClient webClient;

    public NaverMapsClient(NaverMapsProperties naverMapsProperties, WebClient webClient) {
        this.naverMapsProperties = naverMapsProperties;
        // 공용 빈(common/security/SecurityConfig)을 그대로 쓰면 OAuth 호출까지 타임아웃이 바뀌므로,
        // 여기서만 커넥터를 갈아끼운 파생 클라이언트를 만든다. 커넥션 풀도 이 업스트림 전용이다 —
        // 근거는 ExternalApiConnector 참고.
        this.webClient = webClient.mutate()
                .clientConnector(ExternalApiConnector.isolated("naver-maps"))
                .build();
    }

    @Cacheable(value = "naverGeocode",
            key = "T(com.nameless0422.MenuPick.domain.naver.NaverMapsClient)"
                    + ".geocodeCacheKey(#query, #page, #count)",
            // sync=true의 실제 효과. 예전 주석은 "같은 키는 한 스레드만 로딩, 나머지는 대기"라고
            // 적어 두었지만 이 구성에서는 그런 대기가 없다 — RedisConfig가 쓰는
            // RedisCacheManager.builder(factory)는 nonLockingRedisCacheWriter라, 락 없이
            // GET → (미스면) 로더 → PUT을 그냥 실행한다. 만료 순간 같은 키의 동시 요청은 전부
            // 업스트림으로 나간다(#84). 실제 중복 제거가 필요해지면 lockingRedisCacheWriter로
            // 바꿔야 하고, 그때는 새로 생기는 락 대기를 ExternalApiConnector의 지연 예산과 함께
            // 계산해야 한다.
            // 남겨 두는 이유: unless와 함께 쓸 수 없다는 제약(기동 시 IllegalStateException)과,
            // RedisConfig의 fail-open(CacheErrorHandler)이 동기 경로에서도 유지된다는 점.
            // 후자는 예전 주석도 정확했다.
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
                    // 재시도까지 포함한 하드 데드라인 — 근거는 ExternalApiConnector 참고.
                    .block(ExternalApiConnector.TOTAL_TIMEOUT);
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
        // 2xx인데 본문이 오류를 말하는 경우. 여기서 던지지 않으면 깨진 응답이 캐시에 24시간 박힌다.
        if (!GEOCODE_STATUS_OK.equals(result.status())) {
            log.error("네이버 Geocode API 본문 오류: status={}", result.status());
            throw new BusinessException(ErrorCode.NAVER_MAPS_API_ERROR);
        }
        return result;
    }

    @Cacheable(value = "naverReverseGeocode",
            key = "T(com.nameless0422.MenuPick.domain.naver.NaverMapsClient)"
                    + ".reverseGeocodeCacheKey(#coords, #orders)",
            // sync=true의 실제 효과·주의사항은 geocode 주석 참고
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
                    // 재시도까지 포함한 하드 데드라인 — 근거는 ExternalApiConnector 참고.
                    .block(ExternalApiConnector.TOTAL_TIMEOUT);
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
        // status 자체가 없으면 우리가 아는 형식의 응답이 아니다 — 캐시에 넣지 않는다.
        if (result.status() == null || !REVERSE_GEOCODE_OK_CODES.contains(result.status().code())) {
            log.error("네이버 ReverseGeocode API 본문 오류: status={}", result.status());
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
        return joinKey(normalizeQuery(query), page, count);
    }

    public static String reverseGeocodeCacheKey(String coords, String orders) {
        return joinKey(normalizeCoords(coords), orders);
    }

    /**
     * 성분들을 서로 섞이지 않게 이어 붙여 캐시 키를 만든다. 근거는
     * {@code KakaoLocalClient.joinKey}와 같다 — 값 안의 콜론이 구분자와 섞이지 않도록
     * URL 인코딩하고, null(빈 성분)과 값(등호로 시작)을 갈라 서로 다른 인자 조합이
     * 같은 키를 만들지 않게 한다.
     */
    private static String joinKey(Object... parts) {
        StringJoiner joiner = new StringJoiner(":");
        for (Object part : parts) {
            joiner.add(part == null
                    ? ""
                    : "=" + URLEncoder.encode(part.toString(), StandardCharsets.UTF_8));
        }
        return joiner.toString();
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
