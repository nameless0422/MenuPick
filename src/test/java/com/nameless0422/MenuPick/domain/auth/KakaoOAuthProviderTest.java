package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.OAuthUserProfile;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    @DisplayName("provider 이름은 KAKAO이다")
    void getProviderName() {
        assertThat(kakaoOAuthProvider.getProviderName()).isEqualTo("KAKAO");
    }

    @Test
    @DisplayName("인가 코드로 카카오 사용자 프로필을 조회한다")
    void getUserProfile() {
        // 토큰 응답
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"access_token\":\"kakao_access_token\"}")
                .addHeader("Content-Type", "application/json"));

        // 사용자 정보 응답
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "id": 12345,
                          "kakao_account": {
                            "email": "kakao@test.com",
                            "profile": {
                              "nickname": "카카오유저"
                            }
                          }
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        OAuthUserProfile profile = kakaoOAuthProvider.getUserProfile("auth_code");

        assertThat(profile.socialId()).isEqualTo("12345");
        assertThat(profile.email()).isEqualTo("kakao@test.com");
        assertThat(profile.nickname()).isEqualTo("카카오유저");
    }
}
