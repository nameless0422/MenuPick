package com.nameless0422.MenuPick.domain.kakao;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.kakao.dto.KakaoLocalResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoLocalClientTest {

    private MockWebServer mockWebServer;
    private KakaoLocalClient kakaoLocalClient;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String baseUrl = mockWebServer.url("/").toString();
        KakaoLocalProperties properties = new KakaoLocalProperties(
                "test-rest-api-key",
                baseUrl + "v2/local/search/keyword.json",
                baseUrl + "v2/local/search/category.json"
        );
        kakaoLocalClient = new KakaoLocalClient(properties, WebClient.create());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("searchByKeyword - 키워드로 장소를 검색한다")
    void searchByKeyword_success() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "meta": { "total_count": 1, "pageable_count": 1, "is_end": true },
                          "documents": [{
                            "place_name": "진주회관",
                            "address_name": "서울 중구 충무로1가 24-11",
                            "road_address_name": "서울 중구 명동9길 12",
                            "x": "126.985302340908",
                            "y": "37.5612511874743",
                            "phone": "02-776-3525",
                            "place_url": "http://place.map.kakao.com/8005012",
                            "category_name": "음식점 > 한식",
                            "category_group_code": "FD6",
                            "category_group_name": "음식점",
                            "distance": ""
                          }]
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        KakaoLocalResponse.PlaceSearchResult result =
                kakaoLocalClient.searchByKeyword("진주회관", null, null, null, null, null, null, null);

        assertThat(result.meta().totalCount()).isEqualTo(1);
        assertThat(result.meta().isEnd()).isTrue();
        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).placeName()).isEqualTo("진주회관");
        assertThat(result.documents().get(0).phone()).isEqualTo("02-776-3525");
        assertThat(result.documents().get(0).categoryGroupCode()).isEqualTo("FD6");

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("KakaoAK test-rest-api-key");
    }

    @Test
    @DisplayName("searchByKeyword - 선택 파라미터가 요청에 포함된다")
    void searchByKeyword_withOptionalParams() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "meta": { "total_count": 0, "pageable_count": 0, "is_end": true },
                          "documents": []
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        kakaoLocalClient.searchByKeyword("맛집", "FD6", "126.98", "37.56", 1000, 2, 15, "distance");

        RecordedRequest request = mockWebServer.takeRequest();
        String path = request.getPath();
        assertThat(path).contains("category_group_code=FD6");
        assertThat(path).contains("x=126.98");
        assertThat(path).contains("y=37.56");
        assertThat(path).contains("radius=1000");
        assertThat(path).contains("page=2");
        assertThat(path).contains("size=15");
        assertThat(path).contains("sort=distance");
    }

    @Test
    @DisplayName("searchByKeyword - 업스트림 좌표는 반올림하지 않고 원본 그대로 전달한다")
    void searchByKeyword_sendsOriginalCoordinatesUpstream() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        { "meta": { "total_count": 0, "pageable_count": 0, "is_end": true }, "documents": [] }
                        """)
                .addHeader("Content-Type", "application/json"));

        kakaoLocalClient.searchByKeyword("맛집", null, "126.9853023", "37.5612511", null, null, null, null);

        String path = mockWebServer.takeRequest().getPath();
        assertThat(path).contains("x=126.9853023");
        assertThat(path).contains("y=37.5612511");
    }

    @Test
    @DisplayName("searchByKeyword - 중괄호가 포함된 검색어도 URI 템플릿으로 해석되지 않는다")
    void searchByKeyword_bracesAreNotTreatedAsUriTemplate() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        { "meta": { "total_count": 0, "pageable_count": 0, "is_end": true }, "documents": [] }
                        """)
                .addHeader("Content-Type", "application/json"));

        // 예전 uri(String, Function) 방식에서는 {injected}가 URI 변수로 해석되어 호출 자체가 실패했다
        KakaoLocalResponse.PlaceSearchResult result =
                kakaoLocalClient.searchByKeyword("{injected}", null, null, null, null, null, null, null);

        assertThat(result).isNotNull();
        String path = mockWebServer.takeRequest().getPath();
        assertThat(path).contains("%7Binjected%7D");
    }

    @Test
    @DisplayName("searchByKeyword - 업스트림 401은 502(KAKAO_LOCAL_API_ERROR)로 변환")
    void searchByKeyword_upstream401_throwsBadGateway() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(401));

        assertThatThrownBy(() -> kakaoLocalClient.searchByKeyword("진주회관", null, null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KAKAO_LOCAL_API_ERROR);
    }

    @Test
    @DisplayName("searchByKeyword - 업스트림 400은 INVALID_INPUT(400)으로 변환")
    void searchByKeyword_upstream400_throwsInvalidInput() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"errorType\":\"InvalidArgument\"}")
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> kakaoLocalClient.searchByKeyword("진주회관", null, null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("searchByKeyword - 200인데 본문이 비어 있으면 502로 변환 (캐시 AOP 500 차단)")
    void searchByKeyword_emptyBody_throwsBadGateway() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> kakaoLocalClient.searchByKeyword("진주회관", null, null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KAKAO_LOCAL_API_ERROR);
    }

    @Test
    @DisplayName("searchByCategory - 카테고리로 장소를 검색한다")
    void searchByCategory_success() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "meta": { "total_count": 2, "pageable_count": 2, "is_end": true },
                          "documents": [
                            {
                              "place_name": "을지면옥",
                              "address_name": "서울 중구 을지로3가 292-5",
                              "road_address_name": "서울 중구 을지로 119",
                              "x": "126.990848633982",
                              "y": "37.5660138245847",
                              "phone": "02-2267-1737",
                              "place_url": "http://place.map.kakao.com/26853109",
                              "category_name": "음식점 > 한식 > 냉면",
                              "category_group_code": "FD6",
                              "category_group_name": "음식점",
                              "distance": "150"
                            },
                            {
                              "place_name": "명동교자 본점",
                              "address_name": "서울 중구 명동2가 25-2",
                              "road_address_name": "서울 중구 명동10길 29",
                              "x": "126.985643446",
                              "y": "37.563585796",
                              "phone": "02-776-5348",
                              "place_url": "http://place.map.kakao.com/7990409",
                              "category_name": "음식점 > 한식 > 칼국수",
                              "category_group_code": "FD6",
                              "category_group_name": "음식점",
                              "distance": "300"
                            }
                          ]
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        KakaoLocalResponse.PlaceSearchResult result =
                kakaoLocalClient.searchByCategory("FD6", "126.98", "37.56", 1000, null, null, null);

        assertThat(result.meta().totalCount()).isEqualTo(2);
        assertThat(result.documents()).hasSize(2);
        assertThat(result.documents().get(0).placeName()).isEqualTo("을지면옥");
        assertThat(result.documents().get(1).placeName()).isEqualTo("명동교자 본점");
    }

    @Test
    @DisplayName("searchByCategory - API 오류 시 BusinessException 발생")
    void searchByCategory_apiError_throwsBusinessException() {
        // 5xx는 1회 재시도하므로 응답 2개가 필요하다 (재시도까지 실패해야 최종 예외)
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> kakaoLocalClient.searchByCategory("FD6", "126.98", "37.56", 1000, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KAKAO_LOCAL_API_ERROR);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }

    // --- 타임아웃 / 재시도 (이슈 #15) ---

    @Test
    @DisplayName("재시도 - 업스트림 5xx는 1회 재시도하고, 재시도가 성공하면 결과를 반환한다")
    void searchByKeyword_retriesOnceOn5xx() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(503));
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        { "meta": { "total_count": 0, "pageable_count": 0, "is_end": true }, "documents": [] }
                        """)
                .addHeader("Content-Type", "application/json"));

        KakaoLocalResponse.PlaceSearchResult result =
                kakaoLocalClient.searchByKeyword("진주회관", null, null, null, null, null, null, null);

        assertThat(result).isNotNull();
        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("재시도 - 4xx는 재시도하지 않는다 (같은 답이 올 뿐이고 429는 상황을 악화시킨다)")
    void searchByKeyword_doesNotRetryOn4xx() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(400));

        assertThatThrownBy(() -> kakaoLocalClient.searchByKeyword("진주회관", null, null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("타임아웃 - 응답이 3초 안에 오지 않으면 끊고 재시도한다 (톰캣 스레드 보호)")
    void searchByKeyword_responseTimeout() {
        // 응답 타임아웃(3s)보다 길고, 공용 WebClient 빈의 기본값(10s)보다는 짧은 지연.
        // 타임아웃이 걸리지 않았다면 5초 뒤 200(빈 본문)을 받고 요청은 1건에 그친다 —
        // 즉 requestCount == 2가 "3초에 끊고 재시도했다"는 증거다.
        mockWebServer.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS));
        mockWebServer.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS));

        assertThatThrownBy(() -> kakaoLocalClient.searchByKeyword("진주회관", null, null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KAKAO_LOCAL_API_ERROR);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("searchByKeyword - 검색 결과가 없으면 빈 리스트를 반환한다")
    void searchByKeyword_emptyResult() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "meta": { "total_count": 0, "pageable_count": 0, "is_end": true },
                          "documents": []
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        KakaoLocalResponse.PlaceSearchResult result =
                kakaoLocalClient.searchByKeyword("존재하지않는장소", null, null, null, null, null, null, null);

        assertThat(result.meta().totalCount()).isEqualTo(0);
        assertThat(result.documents()).isEmpty();
    }

    // --- 캐시 키 정규화 (이슈 #10) ---

    @Test
    @DisplayName("캐시 키 - 좌표는 소수점 3자리로 반올림되어 미세한 이동이 같은 키로 묶인다")
    void keywordCacheKey_roundsCoordinates() {
        String a = KakaoLocalClient.keywordCacheKey(
                "맛집", "FD6", "126.9853023", "37.5612511", 1000, 1, 15, "distance");
        String b = KakaoLocalClient.keywordCacheKey(
                "맛집", "FD6", "126.9851999", "37.5614999", 1000, 1, 15, "distance");

        assertThat(a).isEqualTo(b);
        assertThat(a).contains("126.985").contains("37.561");
    }

    @Test
    @DisplayName("캐시 키 - 질의는 trim된다")
    void keywordCacheKey_trimsQuery() {
        assertThat(KakaoLocalClient.keywordCacheKey("  맛집  ", null, null, null, null, null, null, null))
                .isEqualTo(KakaoLocalClient.keywordCacheKey("맛집", null, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("캐시 키 - 100m 이상 떨어진 좌표는 다른 키가 된다")
    void keywordCacheKey_distinctForDifferentGrids() {
        String a = KakaoLocalClient.keywordCacheKey("맛집", null, "126.985", "37.561", null, null, null, null);
        String b = KakaoLocalClient.keywordCacheKey("맛집", null, "126.990", "37.561", null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("캐시 키 - 좌표가 숫자가 아니면 원본을 그대로 쓴다 (예외로 터지지 않음)")
    void cacheKey_nonNumericCoordinate() {
        assertThat(KakaoLocalClient.normalizeCoord("not-a-number")).isEqualTo("not-a-number");
        assertThat(KakaoLocalClient.normalizeCoord(null)).isNull();
    }

    @Test
    @DisplayName("캐시 키 - 카테고리 검색도 동일하게 정규화된다")
    void categoryCacheKey_roundsCoordinates() {
        assertThat(KakaoLocalClient.categoryCacheKey("FD6", "126.9853023", "37.5612511", 1000, 1, 15, null))
                .isEqualTo(KakaoLocalClient.categoryCacheKey("FD6", "126.9851", "37.5614", 1000, 1, 15, null));
    }

    /**
     * {@code @Cacheable(key=...)}의 SpEL은 프록시 경유 호출 시점에만 평가되므로,
     * 클래스명 오타 같은 실수가 단위 테스트에서 드러나지 않는다. 여기서 직접 평가해 둔다.
     */
    @Test
    @DisplayName("@Cacheable 키 SpEL이 실제로 평가 가능하다 (searchByKeyword)")
    void keywordCacheableKeyExpression_isEvaluable() throws Exception {
        Method method = KakaoLocalClient.class.getMethod("searchByKeyword",
                String.class, String.class, String.class, String.class,
                Integer.class, Integer.class, Integer.class, String.class);
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("query", "  맛집  ");
        context.setVariable("categoryGroupCode", "FD6");
        context.setVariable("x", "126.9853023");
        context.setVariable("y", "37.5612511");
        context.setVariable("radius", 1000);
        context.setVariable("page", 1);
        context.setVariable("size", 15);
        context.setVariable("sort", "distance");

        Object key = new SpelExpressionParser()
                .parseExpression(method.getAnnotation(Cacheable.class).key())
                .getValue(context);

        assertThat(key).isEqualTo(KakaoLocalClient.keywordCacheKey(
                "맛집", "FD6", "126.9853023", "37.5612511", 1000, 1, 15, "distance"));
    }

    @Test
    @DisplayName("@Cacheable 키 SpEL이 실제로 평가 가능하다 (searchByCategory)")
    void categoryCacheableKeyExpression_isEvaluable() throws Exception {
        Method method = KakaoLocalClient.class.getMethod("searchByCategory",
                String.class, String.class, String.class,
                Integer.class, Integer.class, Integer.class, String.class);
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("categoryGroupCode", "FD6");
        context.setVariable("x", "126.9853023");
        context.setVariable("y", "37.5612511");
        context.setVariable("radius", null);
        context.setVariable("page", null);
        context.setVariable("size", null);
        context.setVariable("sort", null);

        Object key = new SpelExpressionParser()
                .parseExpression(method.getAnnotation(Cacheable.class).key())
                .getValue(context);

        assertThat(key).isEqualTo(KakaoLocalClient.categoryCacheKey(
                "FD6", "126.9853023", "37.5612511", null, null, null, null));
    }
}
