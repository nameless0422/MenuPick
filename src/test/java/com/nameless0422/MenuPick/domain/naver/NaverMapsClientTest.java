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
import org.springframework.web.reactive.function.client.WebClient;

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
    @DisplayName("geocode - API 오류 시 BusinessException 발생")
    void geocode_apiError_throwsBusinessException() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

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
    @DisplayName("reverseGeocode - API 오류 시 BusinessException 발생")
    void reverseGeocode_apiError_throwsBusinessException() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(400));

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
}
