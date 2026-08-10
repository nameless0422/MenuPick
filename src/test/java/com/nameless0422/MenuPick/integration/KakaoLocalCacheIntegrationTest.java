package com.nameless0422.MenuPick.integration;

import com.nameless0422.MenuPick.domain.kakao.KakaoLocalClient;
import com.nameless0422.MenuPick.domain.kakao.dto.KakaoLocalResponse;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 캐시 왕복 통합 테스트.
 *
 * <p>{@code @Cacheable}이 실제 Redis에 붙어 동작하는지 — 즉 같은 파라미터로 두 번 호출하면
 * 업스트림(카카오)을 한 번만 때리는지, 그리고 캐시에 저장된 record DTO가 JSON 직렬화 →
 * 역직렬화를 거쳐 동등한 객체로 복원되는지 확인한다. Redis 없이 목으로만 검증하면
 * {@code GenericJackson2JsonRedisSerializer}가 record를 되살리지 못하는 종류의 문제를 놓친다.
 *
 * <p>업스트림은 {@link MockWebServer}로 대체하고 base URL을 동적 프로퍼티로 주입한다
 * (테스트 전용 설정을 yml에 추가하지 않기 위한 선택).
 */
@SpringBootTest
@ActiveProfiles("integration")
class KakaoLocalCacheIntegrationTest extends AbstractIntegrationTest {

    private static final String KEYWORD_CACHE = "kakaoKeywordSearch";

    private static final String RESPONSE_BODY = """
            {
              "meta": {"total_count": 1, "pageable_count": 1, "is_end": true},
              "documents": [
                {
                  "place_name": "진주회관",
                  "address_name": "서울 중구 충무로1가 24-11",
                  "road_address_name": "서울 중구 명동9길 12",
                  "x": "126.985302",
                  "y": "37.561251",
                  "phone": "02-776-3525",
                  "place_url": "http://place.map.kakao.com/8005012",
                  "category_name": "음식점 > 한식",
                  "category_group_code": "FD6",
                  "category_group_name": "음식점",
                  "distance": "120"
                }
              ]
            }
            """;

    /** MySQL/Redis 컨테이너와 마찬가지로 JVM당 1회만 띄운다. */
    private static final MockWebServer UPSTREAM = new MockWebServer();

    static {
        try {
            UPSTREAM.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void kakaoProperties(DynamicPropertyRegistry registry) {
        registry.add("kakao.local.keyword-search-base-url",
                () -> UPSTREAM.url("/v2/local/search/keyword.json").toString());
        registry.add("kakao.local.category-search-base-url",
                () -> UPSTREAM.url("/v2/local/search/category.json").toString());
    }

    @Autowired private KakaoLocalClient kakaoLocalClient;
    @Autowired private CacheManager cacheManager;

    /** {@code getRequestCount()}는 서버 생성 이후 누적이라 테스트 시작 시점을 기준선으로 잡는다. */
    private int baseline;

    @BeforeEach
    void clearCache() {
        // Redis 컨테이너는 JVM 전체가 공유하므로 테스트 간 잔여 엔트리를 지운다.
        cacheManager.getCache(KEYWORD_CACHE).clear();
        baseline = UPSTREAM.getRequestCount();
    }

    private int upstreamCalls() {
        return UPSTREAM.getRequestCount() - baseline;
    }

    private void enqueueSuccess(int times) {
        for (int i = 0; i < times; i++) {
            UPSTREAM.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json;charset=UTF-8")
                    .setBody(RESPONSE_BODY));
        }
    }

    @Test
    @DisplayName("동일 파라미터로 두 번 호출하면 업스트림은 한 번만 호출되고, 두 번째 결과는 Redis에서 복원된다")
    void sameParameters_hitsUpstreamOnce_andRoundTripsThroughRedis() {
        enqueueSuccess(2);

        KakaoLocalResponse.PlaceSearchResult first = kakaoLocalClient.searchByKeyword(
                "진주회관", "FD6", "126.9853020", "37.5612510", 500, 1, 15, "accuracy");
        KakaoLocalResponse.PlaceSearchResult second = kakaoLocalClient.searchByKeyword(
                "진주회관", "FD6", "126.9853020", "37.5612510", 500, 1, 15, "accuracy");

        assertThat(upstreamCalls()).isEqualTo(1);

        // 두 번째 결과는 캐시에서 역직렬화된 별개 인스턴스지만 값은 동일해야 한다
        // (record + GenericJackson2JsonRedisSerializer 왕복 검증).
        assertThat(second).isNotSameAs(first).isEqualTo(first);
        assertThat(second.documents()).hasSize(1);
        assertThat(second.documents().get(0).placeName()).isEqualTo("진주회관");
        assertThat(second.documents().get(0).categoryGroupCode()).isEqualTo("FD6");
        assertThat(second.meta().isEnd()).isTrue();

        // 캐시에 실제로 엔트리가 들어갔는지도 확인한다.
        String key = KakaoLocalClient.keywordCacheKey(
                "진주회관", "FD6", "126.9853020", "37.5612510", 500, 1, 15, "accuracy");
        assertThat(cacheManager.getCache(KEYWORD_CACHE).get(key)).isNotNull();
    }

    @Test
    @DisplayName("좌표가 소수점 3자리 이하로만 다르면 같은 캐시 키를 써 업스트림을 다시 호출하지 않는다")
    void nearlyIdenticalCoordinates_shareCacheEntry() {
        enqueueSuccess(2);

        kakaoLocalClient.searchByKeyword("맛집", null, "126.97810", "37.56610", null, null, null, null);
        kakaoLocalClient.searchByKeyword("맛집", null, "126.97814", "37.56612", null, null, null, null);

        assertThat(upstreamCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("질의어가 다르면 캐시 키가 달라 업스트림을 다시 호출한다")
    void differentQuery_missesCache() {
        enqueueSuccess(2);

        kakaoLocalClient.searchByKeyword("김밥", null, null, null, null, null, null, null);
        kakaoLocalClient.searchByKeyword("국밥", null, null, null, null, null, null, null);

        assertThat(upstreamCalls()).isEqualTo(2);
    }
}
