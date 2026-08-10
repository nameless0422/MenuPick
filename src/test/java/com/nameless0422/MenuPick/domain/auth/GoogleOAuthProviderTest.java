package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.OAuthUserProfile;
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

class GoogleOAuthProviderTest {

    private MockWebServer mockWebServer;
    private GoogleOAuthProvider googleOAuthProvider;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String baseUrl = mockWebServer.url("/").toString();
        OAuthProperties.ProviderConfig config = new OAuthProperties.ProviderConfig(
                "test-client-id", "test-client-secret",
                baseUrl + "token",
                baseUrl + "userinfo",
                "http://localhost:3000/callback"
        );
        OAuthProperties properties = new OAuthProperties(null, config);

        googleOAuthProvider = new GoogleOAuthProvider(properties, WebClient.create());
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    private void enqueueJson(String body) {
        mockWebServer.enqueue(new MockResponse()
                .setBody(body)
                .addHeader("Content-Type", "application/json"));
    }

    private void enqueueJson(int status, String body) {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(status)
                .setBody(body)
                .addHeader("Content-Type", "application/json"));
    }

    @Test
    @DisplayName("provider 이름은 GOOGLE이다")
    void getProviderName() {
        assertThat(googleOAuthProvider.getProviderName()).isEqualTo("GOOGLE");
    }

    @Test
    @DisplayName("인가 코드로 구글 사용자 프로필을 조회한다")
    void getUserProfile() {
        enqueueJson("{\"access_token\":\"google_access_token\"}");
        enqueueJson("""
                {
                  "id": "google_67890",
                  "email": "google@test.com",
                  "verified_email": true,
                  "name": "구글유저"
                }
                """);

        OAuthUserProfile profile = googleOAuthProvider.getUserProfile("auth_code");

        assertThat(profile.socialId()).isEqualTo("google_67890");
        assertThat(profile.email()).isEqualTo("google@test.com");
        assertThat(profile.nickname()).isEqualTo("구글유저");
        assertThat(profile.emailVerified()).isTrue();
    }

    @Test
    @DisplayName("verified_email 플래그가 없으면 emailVerified는 false다")
    void getUserProfile_missingVerifiedFlag() {
        enqueueJson("{\"access_token\":\"google_access_token\"}");
        enqueueJson("""
                {
                  "id": "google_67890",
                  "email": "google@test.com",
                  "name": "구글유저"
                }
                """);

        OAuthUserProfile profile = googleOAuthProvider.getUserProfile("auth_code");

        assertThat(profile.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("토큰 교환이 4xx면 OAUTH_INVALID_CODE(401)로 변환하고 프로필 조회를 시도하지 않는다")
    void getUserProfile_tokenExchange4xx_throwsOAuthInvalidCode() {
        enqueueJson(400, "{\"error\":\"invalid_grant\"}");

        assertThatThrownBy(() -> googleOAuthProvider.getUserProfile("expired_code"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_INVALID_CODE);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("200이지만 access_token이 없으면 OAUTH_INVALID_CODE로 처리한다")
    void getUserProfile_missingAccessToken_throwsOAuthInvalidCode() {
        enqueueJson("{\"scope\":\"openid email\"}");

        assertThatThrownBy(() -> googleOAuthProvider.getUserProfile("auth_code"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_INVALID_CODE);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("프로필 조회가 4xx면 OAUTH_INVALID_CODE로 변환한다")
    void getUserProfile_userInfo4xx_throwsOAuthInvalidCode() {
        enqueueJson("{\"access_token\":\"google_access_token\"}");
        enqueueJson(401, "{\"error\":\"invalid_token\"}");

        assertThatThrownBy(() -> googleOAuthProvider.getUserProfile("auth_code"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_INVALID_CODE);
    }

    @Test
    @DisplayName("업스트림 5xx는 인가 코드 문제가 아니므로 401이 아닌 500 계열로 처리한다")
    void getUserProfile_upstream5xx_notTreatedAsInvalidCode() {
        enqueueJson(503, "{\"error\":\"unavailable\"}");

        assertThatThrownBy(() -> googleOAuthProvider.getUserProfile("auth_code"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR);
    }

    @Test
    @DisplayName("email·name 스코프가 없어도 NPE 없이 null로 반환한다")
    void getUserProfile_missingEmailAndName() {
        enqueueJson("{\"access_token\":\"google_access_token\"}");
        enqueueJson("{\"id\": \"google_67890\"}");

        OAuthUserProfile profile = googleOAuthProvider.getUserProfile("auth_code");

        assertThat(profile.socialId()).isEqualTo("google_67890");
        assertThat(profile.email()).isNull();
        assertThat(profile.nickname()).isNull();
        assertThat(profile.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("프로필 응답에 id가 없으면 OAUTH_INVALID_CODE로 처리한다")
    void getUserProfile_missingId_throwsOAuthInvalidCode() {
        enqueueJson("{\"access_token\":\"google_access_token\"}");
        enqueueJson("{\"email\":\"google@test.com\"}");

        assertThatThrownBy(() -> googleOAuthProvider.getUserProfile("auth_code"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_INVALID_CODE);
    }

    @Test
    @DisplayName("토큰 교환 바디는 폼 인코딩되어 파라미터 인젝션이 되지 않는다")
    void getUserProfile_codeIsFormEncoded() throws Exception {
        enqueueJson("{\"access_token\":\"google_access_token\"}");
        enqueueJson("{\"id\": \"google_67890\"}");

        googleOAuthProvider.getUserProfile("abc&redirect_uri=https://evil.example");

        RecordedRequest tokenRequest = mockWebServer.takeRequest();
        String body = tokenRequest.getBody().readUtf8();

        assertThat(body.split("redirect_uri=", -1).length - 1).isEqualTo(1);
        assertThat(body).contains("code=abc%26redirect_uri%3Dhttps%3A%2F%2Fevil.example");
    }
}
