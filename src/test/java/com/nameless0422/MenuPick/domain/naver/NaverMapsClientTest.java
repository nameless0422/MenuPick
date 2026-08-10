package com.nameless0422.MenuPick.domain.naver;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.naver.dto.NaverMapsResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NaverMapsClientTest {

    private MockWebServer mockWebServer;
    private NaverMapsClient naverMapsClient;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String baseUrl = mockWebServer.url("/").toString();
        NaverMapsProperties properties = new NaverMapsProperties(
                "test-client-id", "test-client-secret",
                baseUrl + "map-geocode/v2/geocode",
                baseUrl + "map-reversegeocode/v2/gc"
        );
        naverMapsClient = new NaverMapsClient(properties, WebClient.create());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("geocode - 주소로 좌표를 조회한다")
    void geocode_success() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "status": "OK",
                          "meta": { "totalCount": 1, "page": 1, "count": 1 },
                          "addresses": [{
                            "roadAddress": "서울특별시 중구 세종대로 110",
                            "jibunAddress": "서울특별시 중구 태평로1가 31",
                            "x": "126.9783882",
                            "y": "37.5666103",
                            "distance": 0.0
                          }]
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        NaverMapsResponse.GeocodeResult result = naverMapsClient.geocode("서울시청", null, null);

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.meta().totalCount()).isEqualTo(1);
        assertThat(result.addresses()).hasSize(1);
        assertThat(result.addresses().get(0).roadAddress()).isEqualTo("서울특별시 중구 세종대로 110");
        assertThat(result.addresses().get(0).x()).isEqualTo("126.9783882");
        assertThat(result.addresses().get(0).y()).isEqualTo("37.5666103");

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("x-ncp-apigw-api-key-id")).isEqualTo("test-client-id");
        assertThat(request.getHeader("x-ncp-apigw-api-key")).isEqualTo("test-client-secret");
    }

    @Test
    @DisplayName("geocode - 선택 파라미터(page, count)가 요청에 포함된다")
    void geocode_withOptionalParams() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "status": "OK",
                          "meta": { "totalCount": 10, "page": 2, "count": 5 },
                          "addresses": []
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        naverMapsClient.geocode("서울", 2, 5);

        RecordedRequest request = mockWebServer.takeRequest();
        String path = request.getPath();
        assertThat(path).contains("page=2");
        assertThat(path).contains("count=5");
    }

    @Test
    @DisplayName("geocode - 중괄호가 포함된 질의도 URI 템플릿으로 해석되지 않는다")
    void geocode_bracesAreNotTreatedAsUriTemplate() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "status": "OK",
                          "meta": { "totalCount": 0, "page": 1, "count": 0 },
                          "addresses": []
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        NaverMapsResponse.GeocodeResult result = naverMapsClient.geocode("{injected}", null, null);

        assertThat(result).isNotNull();
        assertThat(mockWebServer.takeRequest().getPath()).contains("%7Binjected%7D");
    }

    @Test
    @DisplayName("geocode - 업스트림 5xx는 502(NAVER_MAPS_API_ERROR)로 변환")
    void geocode_upstream5xx_throwsBadGateway() {
        // 5xx는 1회 재시도하므로 응답 2개가 필요하다 (재시도까지 실패해야 최종 예외)
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> naverMapsClient.geocode("서울시청", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NAVER_MAPS_API_ERROR);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }

    // --- 재시도 (이슈 #15) ---

    @Test
    @DisplayName("재시도 - 업스트림 5xx는 1회 재시도하고, 재시도가 성공하면 결과를 반환한다")
    void geocode_retriesOnceOn5xx() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(502));
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "status": "OK",
                          "meta": { "totalCount": 0, "page": 1, "count": 0 },
                          "addresses": []
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        NaverMapsResponse.GeocodeResult result = naverMapsClient.geocode("서울시청", null, null);

        assertThat(result.status()).isEqualTo("OK");
        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("재시도 - 4xx는 재시도하지 않는다 (429 재시도는 쿼터 소진을 가속한다)")
    void reverseGeocode_doesNotRetryOn4xx() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(429));

        assertThatThrownBy(() -> naverMapsClient.reverseGeocode("126.978,37.566", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NAVER_MAPS_API_ERROR);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("geocode - 업스트림 400은 INVALID_INPUT(400)으로 변환")
    void geocode_upstream400_throwsInvalidInput() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"errorMessage\":\"query is invalid\"}")
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> naverMapsClient.geocode("!!!", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("geocode - 업스트림 401은 502를 유지한다 (우리 자격증명 문제)")
    void geocode_upstream401_throwsBadGateway() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(401));

        assertThatThrownBy(() -> naverMapsClient.geocode("서울시청", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NAVER_MAPS_API_ERROR);
    }

    @Test
    @DisplayName("geocode - 200인데 본문이 비어 있으면 502로 변환 (캐시 AOP 500 차단)")
    void geocode_emptyBody_throwsBadGateway() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> naverMapsClient.geocode("서울시청", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NAVER_MAPS_API_ERROR);
    }

    @Test
    @DisplayName("geocode - 검색 결과가 없으면 빈 리스트를 반환한다")
    void geocode_emptyResult() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "status": "OK",
                          "meta": { "totalCount": 0, "page": 1, "count": 0 },
                          "addresses": []
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        NaverMapsResponse.GeocodeResult result = naverMapsClient.geocode("존재하지않는주소", null, null);

        assertThat(result.meta().totalCount()).isEqualTo(0);
        assertThat(result.addresses()).isEmpty();
    }

    @Test
    @DisplayName("reverseGeocode - 좌표로 주소를 조회한다")
    void reverseGeocode_success() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "status": { "code": 0, "name": "ok", "message": "done" },
                          "results": [{
                            "name": "roadaddr",
                            "region": {
                              "area0": { "name": "kr", "coords": { "center": { "crs": "", "x": "0", "y": "0" } } },
                              "area1": { "name": "서울특별시", "coords": { "center": { "crs": "", "x": "0", "y": "0" } } },
                              "area2": { "name": "중구", "coords": { "center": { "crs": "", "x": "0", "y": "0" } } },
                              "area3": { "name": "태평로1가", "coords": { "center": { "crs": "", "x": "0", "y": "0" } } },
                              "area4": { "name": "", "coords": { "center": { "crs": "", "x": "0", "y": "0" } } }
                            },
                            "land": {
                              "type": "1",
                              "number1": "31",
                              "number2": "",
                              "addition0": { "type": "building", "value": "서울특별시청" },
                              "name": "세종대로"
                            }
                          }]
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        NaverMapsResponse.ReverseGeocodeResult result =
                naverMapsClient.reverseGeocode("126.978,37.566", "roadaddr");

        assertThat(result.status().code()).isEqualTo(0);
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).name()).isEqualTo("roadaddr");
        assertThat(result.results().get(0).region().area1().name()).isEqualTo("서울특별시");

        RecordedRequest request = mockWebServer.takeRequest();
        String path = request.getPath();
        assertThat(path).contains("output=json");
        assertThat(path).containsPattern("sourcecrs=EPSG(%3A|:)4326");
        assertThat(path).contains("orders=roadaddr");
    }

    @Test
    @DisplayName("reverseGeocode - 업스트림 좌표는 반올림하지 않고 원본 그대로 전달한다")
    void reverseGeocode_sendsOriginalCoordinatesUpstream() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        { "status": { "code": 0, "name": "ok", "message": "done" }, "results": [] }
                        """)
                .addHeader("Content-Type", "application/json"));

        naverMapsClient.reverseGeocode("126.9783882,37.5666103", null);

        String path = mockWebServer.takeRequest().getPath();
        assertThat(path).containsPattern("coords=126.9783882(%2C|,)37.5666103");
    }

    @Test
    @DisplayName("reverseGeocode - 업스트림 400은 INVALID_INPUT(400)으로 변환")
    void reverseGeocode_upstream400_throwsInvalidInput() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(400));

        assertThatThrownBy(() -> naverMapsClient.reverseGeocode("126.978,37.566", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("reverseGeocode - 업스트림 429는 502를 유지한다")
    void reverseGeocode_upstream429_throwsBadGateway() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(429));

        assertThatThrownBy(() -> naverMapsClient.reverseGeocode("126.978,37.566", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NAVER_MAPS_API_ERROR);
    }

    @Test
    @DisplayName("reverseGeocode - 200인데 본문이 비어 있으면 502로 변환")
    void reverseGeocode_emptyBody_throwsBadGateway() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> naverMapsClient.reverseGeocode("126.978,37.566", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NAVER_MAPS_API_ERROR);
    }

    @Test
    @DisplayName("요청 헤더에 API 키가 포함된다")
    void requestHeaders_containApiKeys() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "status": { "code": 0, "name": "ok", "message": "done" },
                          "results": []
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        naverMapsClient.reverseGeocode("126.978,37.566", null);

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("x-ncp-apigw-api-key-id")).isEqualTo("test-client-id");
        assertThat(request.getHeader("x-ncp-apigw-api-key")).isEqualTo("test-client-secret");
    }

    // --- 캐시 키 정규화 (이슈 #10) ---

    @Test
    @DisplayName("캐시 키 - reverseGeocode 좌표는 소수점 3자리로 반올림된다")
    void reverseGeocodeCacheKey_roundsCoordinates() {
        String a = NaverMapsClient.reverseGeocodeCacheKey("126.9783882,37.5661103", "roadaddr");
        String b = NaverMapsClient.reverseGeocodeCacheKey("126.9781,37.5664", "roadaddr");

        assertThat(a).isEqualTo(b);
        assertThat(a).startsWith("126.978,37.566");
    }

    @Test
    @DisplayName("캐시 키 - 100m 이상 떨어진 좌표는 다른 키가 된다")
    void reverseGeocodeCacheKey_distinctForDifferentGrids() {
        assertThat(NaverMapsClient.reverseGeocodeCacheKey("126.978,37.566", null))
                .isNotEqualTo(NaverMapsClient.reverseGeocodeCacheKey("126.988,37.566", null));
    }

    @Test
    @DisplayName("캐시 키 - geocode 질의는 trim된다")
    void geocodeCacheKey_trimsQuery() {
        assertThat(NaverMapsClient.geocodeCacheKey("  서울시청  ", 1, 10))
                .isEqualTo(NaverMapsClient.geocodeCacheKey("서울시청", 1, 10));
    }

    @Test
    @DisplayName("캐시 키 - 숫자가 아닌 좌표는 원본을 그대로 쓴다")
    void reverseGeocodeCacheKey_nonNumericCoordinate() {
        assertThat(NaverMapsClient.normalizeCoords("not,coords")).isEqualTo("not,coords");
        assertThat(NaverMapsClient.normalizeCoords(null)).isNull();
    }

    /**
     * {@code @Cacheable(key=...)}의 SpEL은 프록시 경유 호출 시점에만 평가되므로,
     * 클래스명 오타 같은 실수가 단위 테스트에서 드러나지 않는다. 여기서 직접 평가해 둔다.
     */
    @Test
    @DisplayName("@Cacheable 키 SpEL이 실제로 평가 가능하다 (geocode)")
    void geocodeCacheableKeyExpression_isEvaluable() throws Exception {
        Method method = NaverMapsClient.class.getMethod("geocode",
                String.class, Integer.class, Integer.class);
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("query", "  서울시청  ");
        context.setVariable("page", 1);
        context.setVariable("count", 10);

        Object key = new SpelExpressionParser()
                .parseExpression(method.getAnnotation(Cacheable.class).key())
                .getValue(context);

        assertThat(key).isEqualTo(NaverMapsClient.geocodeCacheKey("서울시청", 1, 10));
    }

    @Test
    @DisplayName("@Cacheable 키 SpEL이 실제로 평가 가능하다 (reverseGeocode)")
    void reverseGeocodeCacheableKeyExpression_isEvaluable() throws Exception {
        Method method = NaverMapsClient.class.getMethod("reverseGeocode",
                String.class, String.class);
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("coords", "126.9783882,37.5661103");
        context.setVariable("orders", null);

        Object key = new SpelExpressionParser()
                .parseExpression(method.getAnnotation(Cacheable.class).key())
                .getValue(context);

        assertThat(key).isEqualTo(NaverMapsClient.reverseGeocodeCacheKey("126.9783882,37.5661103", null));
        assertThat(key).isEqualTo("126.978,37.566:null");
    }
}
