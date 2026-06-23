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
import org.springframework.web.reactive.function.client.WebClient;

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
    @DisplayName("searchByKeyword - API 오류 시 BusinessException 발생")
    void searchByKeyword_apiError_throwsBusinessException() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(401));

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
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> kakaoLocalClient.searchByCategory("FD6", "126.98", "37.56", 1000, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KAKAO_LOCAL_API_ERROR);
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
}
