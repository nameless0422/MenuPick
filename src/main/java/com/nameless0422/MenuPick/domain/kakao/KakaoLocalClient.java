package com.nameless0422.MenuPick.domain.kakao;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.http.ExternalApiConnector;
import com.nameless0422.MenuPick.domain.kakao.dto.KakaoLocalResponse;
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
import java.util.StringJoiner;
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

    /** 재시도 사이 고정 대기. 업스트림이 순간적으로 흔들린 경우를 흡수할 정도만 준다. */
    private static final Duration RETRY_DELAY = Duration.ofMillis(200);

    private final KakaoLocalProperties kakaoLocalProperties;
    private final WebClient webClient;

    public KakaoLocalClient(KakaoLocalProperties kakaoLocalProperties, WebClient webClient) {
        this.kakaoLocalProperties = kakaoLocalProperties;
        // 공용 빈(common/security/SecurityConfig)을 그대로 쓰면 OAuth 호출까지 타임아웃이 바뀌므로,
        // 여기서만 커넥터를 갈아끼운 파생 클라이언트를 만든다. 커넥션 풀도 이 업스트림 전용이다 —
        // 근거는 ExternalApiConnector 참고.
        this.webClient = webClient.mutate()
                .clientConnector(ExternalApiConnector.isolated("kakao-local"))
                .build();
    }

    @Cacheable(value = "kakaoKeywordSearch",
            key = "T(com.nameless0422.MenuPick.domain.kakao.KakaoLocalClient)"
                    + ".keywordCacheKey(#query, #categoryGroupCode, #x, #y, #radius, #page, #size, #sort)",
            // sync=true의 실제 효과. 예전 주석은 "같은 키는 한 스레드만 로딩, 나머지는 대기"라고
            // 적어 두었지만 이 구성에서는 그런 대기가 없다 — RedisConfig가 쓰는
            // RedisCacheManager.builder(factory)는 nonLockingRedisCacheWriter라, 락도 synchronized도
            // 없이 GET → (미스면) 로더 → PUT을 그냥 실행한다. 즉 인기 좌표의 엔트리가 만료되는
            // 순간 그 키를 노리는 동시 요청 N건이 전부 업스트림으로 나간다(#84).
            //   * 실제 중복 제거가 필요해지면 RedisCacheWriter.lockingRedisCacheWriter로 바꿔야
            //     한다. 다만 그러면 락 대기 시간이 새로 생겨 ExternalApiConnector의 지연 예산과
            //     함께 다시 계산해야 하므로, 지금은 스탬피드를 감수하고 사실만 적어 둔다.
            //   * TTL이 1시간이고 같은 키에 동시에 몰리는 사용자 수가 작아 실질 손해가 크지 않다.
            // 남겨 두는 이유는 두 가지다.
            //   * unless와 함께 쓸 수 없다는 제약이 그대로다(기동 시 IllegalStateException).
            //     execute()가 null 결과를 이미 예외로 바꾸므로 unless는 어차피 죽은 코드였다.
            //   * RedisConfig의 fail-open(CacheErrorHandler)은 유지된다 — 동기 경로도
            //     AbstractCacheInvoker.doGet(cache, key, loader)를 거쳐 캐시 오류를 미스로 강등한다.
            //     이 부분은 예전 주석도 정확했다.
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
            // sync=true의 실제 효과·주의사항은 searchByKeyword 주석 참고
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
                    // 재시도까지 포함한 하드 데드라인. 단계별 상한의 합은 프론트엔드 axios의
                    // 15초 타임아웃을 넘어서므로 그 전에 우리 쪽에서 끊는다.
                    .block(ExternalApiConnector.TOTAL_TIMEOUT);
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
        return joinKey(normalizeQuery(query), categoryGroupCode,
                normalizeCoord(x), normalizeCoord(y), radius, page, size, sort);
    }

    public static String categoryCacheKey(String categoryGroupCode, String x, String y,
                                          Integer radius, Integer page, Integer size, String sort) {
        return joinKey(categoryGroupCode, normalizeCoord(x), normalizeCoord(y),
                radius, page, size, sort);
    }

    /**
     * 성분들을 서로 섞이지 않게 이어 붙여 캐시 키를 만든다.
     *
     * <p>예전에는 {@code a + ':' + b + ...}로 단순 연결했다. 사용자 질의에는 {@code :}가 들어갈 수
     * 있고({@code @Size(max=100)} 외에 제약이 없다) null은 문자열 {@code "null"}이 되므로,
     * 서로 다른 인자 조합이 <b>같은 키</b>를 만들 수 있었다.
     *
     * <pre>
     *   query="김밥:FD6", categoryGroupCode=null  →  "김밥:FD6:null:..."
     *   query="김밥",     categoryGroupCode="FD6" →  "김밥:FD6:null:..."   ← 같은 키
     * </pre>
     *
     * <p>충돌하면 서로 다른 검색의 결과가 서로에게 응답된다. 확률은 낮지만 사용자가 질의만으로
     * 조작할 수 있고, 한 번 박히면 TTL(1시간) 동안 유지된다.
     *
     * <p>각 성분을 URL 인코딩하면 값 안의 {@code :}가 {@code %3A}가 되어 구분자와 섞이지 않는다.
     * null과 빈 문자열도 갈라야 하므로 값 앞에 {@code =}를 붙인다 — 인코딩된 값 안에서
     * {@code =}는 {@code %3D}가 되므로 이 표시가 값의 일부로 나타날 수 없다.
     * 즉 null은 빈 성분, 값은 항상 {@code =}로 시작하는 성분이다.
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
