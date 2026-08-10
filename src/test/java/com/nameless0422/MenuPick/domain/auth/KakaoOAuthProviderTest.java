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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoOAuthProviderTest {

    private MockWebServer mockWebServer;
    private KakaoOAuthProvider kakaoOAuthProvider;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String baseUrl = mockWebServer.url("/").toString();
        OAuthProperties.ProviderConfig config = new OAuthProperties.ProviderConfig(
                "test-client-id", "test-client-secret",
                baseUrl + "oauth/token",
                baseUrl + "v2/user/me",
                "http://localhost:3000/callback"
        );
        OAuthProperties properties = new OAuthProperties(config, null);

        kakaoOAuthProvider = new KakaoOAuthProvider(properties, WebClient.create());
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
    @DisplayName("provider 이름은 KAKAO이다")
    void getProviderName() {
        assertThat(kakaoOAuthProvider.getProviderName()).isEqualTo("KAKAO");
    }

    @Test
    @DisplayName("인가 코드로 카카오 사용자 프로필을 조회한다")
    void getUserProfile() {
        enqueueJson("{\"access_token\":\"kakao_access_token\"}");
        enqueueJson("""
                {
                  "id": 12345,
                  "kakao_account": {
                    "email": "kakao@test.com",
                    "is_email_valid": true,
                    "is_email_verified": true,
                    "profile": {
                      "nickname": "카카오유저"
                    }
                  }
                }
                """);

        OAuthUserProfile profile = kakaoOAuthProvider.getUserProfile("auth_code");

        assertThat(profile.socialId()).isEqualTo("12345");
        assertThat(profile.email()).isEqualTo("kakao@test.com");
        assertThat(profile.nickname()).isEqualTo("카카오유저");
        assertThat(profile.emailVerified()).isTrue();
    }

    @Test
    @DisplayName("이메일 검증 플래그가 없거나 false면 emailVerified는 false다")
    void getUserProfile_unverifiedEmail() {
        enqueueJson("{\"access_token\":\"kakao_access_token\"}");
        enqueueJson("""
                {
                  "id": 12345,
                  "kakao_account": {
                    "email": "kakao@test.com",
                    "is_email_valid": true,
                    "is_email_verified": false,
                    "profile": {
                      "nickname": "카카오유저"
                    }
                  }
                }
                """);

        OAuthUserProfile profile = kakaoOAuthProvider.getUserProfile("auth_code");

        assertThat(profile.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("토큰 교환이 4xx면 OAUTH_INVALID_CODE(401)로 변환하고 프로필 조회를 시도하지 않는다")
    void getUserProfile_tokenExchange4xx_throwsOAuthInvalidCode() {
        enqueueJson(400, "{\"error\":\"invalid_grant\"}");

        assertThatThrownBy(() -> kakaoOAuthProvider.getUserProfile("expired_code"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_INVALID_CODE);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("200이지만 access_token이 없으면 OAUTH_INVALID_CODE로 처리한다 (카카오 오류 응답 패턴)")
    void getUserProfile_missingAccessToken_throwsOAuthInvalidCode() {
        enqueueJson("{\"error\":\"invalid_grant\",\"error_description\":\"authorization code not found\"}");

        assertThatThrownBy(() -> kakaoOAuthProvider.getUserProfile("auth_code"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_INVALID_CODE);

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("프로필 조회가 4xx면 OAUTH_INVALID_CODE로 변환한다")
    void getUserProfile_userInfo4xx_throwsOAuthInvalidCode() {
        enqueueJson("{\"access_token\":\"kakao_access_token\"}");
        enqueueJson(401, "{\"msg\":\"invalid token\"}");

        assertThatThrownBy(() -> kakaoOAuthProvider.getUserProfile("auth_code"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_INVALID_CODE);
    }

    @Test
    @DisplayName("업스트림 5xx는 인가 코드 문제가 아니므로 401이 아닌 500 계열로 처리한다")
    void getUserProfile_upstream5xx_notTreatedAsInvalidCode() {
        enqueueJson(500, "{\"msg\":\"internal error\"}");

        assertThatThrownBy(() -> kakaoOAuthProvider.getUserProfile("auth_code"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR);
    }

    @Test
    @DisplayName("kakao_account·profile이 없어도 NPE 없이 email·nickname을 null로 반환한다")
    void getUserProfile_missingKakaoAccount_returnsNullFields() {
        enqueueJson("{\"access_token\":\"kakao_access_token\"}");
        enqueueJson("{\"id\": 12345}");

        OAuthUserProfile profile = kakaoOAuthProvider.getUserProfile("auth_code");

        assertThat(profile.socialId()).isEqualTo("12345");
        assertThat(profile.email()).isNull();
        assertThat(profile.nickname()).isNull();
        assertThat(profile.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("profile 동의만 거부돼도 nickname만 null이고 이메일은 그대로 읽는다")
    void getUserProfile_missingProfileObject() {
        enqueueJson("{\"access_token\":\"kakao_access_token\"}");
        enqueueJson("""
                {
                  "id": 12345,
                  "kakao_account": {
                    "email": "kakao@test.com",
                    "is_email_valid": true,
                    "is_email_verified": true
                  }
                }
                """);

        OAuthUserProfile profile = kakaoOAuthProvider.getUserProfile("auth_code");

        assertThat(profile.email()).isEqualTo("kakao@test.com");
        assertThat(profile.nickname()).isNull();
        assertThat(profile.emailVerified()).isTrue();
    }

    @Test
    @DisplayName("프로필 응답에 id가 없으면 OAUTH_INVALID_CODE로 처리한다 (socialId \"null\" 공유 방지)")
    void getUserProfile_missingId_throwsOAuthInvalidCode() {
        enqueueJson("{\"access_token\":\"kakao_access_token\"}");
        enqueueJson("{\"kakao_account\": {}}");

        assertThatThrownBy(() -> kakaoOAuthProvider.getUserProfile("auth_code"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_INVALID_CODE);
    }

    @Test
    @DisplayName("인가 코드의 &·= 는 폼 인코딩돼 파라미터 인젝션이 되지 않는다")
    void getUserProfile_codeWithSpecialChars_isFormEncoded() throws Exception {
        enqueueJson("{\"access_token\":\"kakao_access_token\"}");
        enqueueJson("{\"id\": 12345}");

        kakaoOAuthProvider.getUserProfile("abc&client_id=attacker&x=1");

        RecordedRequest tokenRequest = mockWebServer.takeRequest();
        String body = tokenRequest.getBody().readUtf8();

        // client_id 파라미터는 정확히 한 번만 나타나야 한다
        assertThat(body.split("client_id=", -1).length - 1).isEqualTo(1);
        assertThat(body).contains("code=abc%26client_id%3Dattacker%26x%3D1");
        assertThat(URLDecoder.decode(body, StandardCharsets.UTF_8))
                .contains("code=abc&client_id=attacker&x=1");
    }
}
