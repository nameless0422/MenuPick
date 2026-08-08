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

    @Test
    @DisplayName("provider 이름은 GOOGLE이다")
    void getProviderName() {
        assertThat(googleOAuthProvider.getProviderName()).isEqualTo("GOOGLE");
    }

    @Test
    @DisplayName("인가 코드로 구글 사용자 프로필을 조회한다")
    void getUserProfile() {
        // 토큰 응답
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"access_token\":\"google_access_token\"}")
                .addHeader("Content-Type", "application/json"));

        // 사용자 정보 응답
        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "id": "google_67890",
                          "email": "google@test.com",
                          "verified_email": true,
                          "name": "구글유저"
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        OAuthUserProfile profile = googleOAuthProvider.getUserProfile("auth_code");

        assertThat(profile.socialId()).isEqualTo("google_67890");
        assertThat(profile.email()).isEqualTo("google@test.com");
        assertThat(profile.nickname()).isEqualTo("구글유저");
        assertThat(profile.emailVerified()).isTrue();
    }

    @Test
    @DisplayName("verified_email 플래그가 없으면 emailVerified는 false다")
    void getUserProfile_missingVerifiedFlag() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"access_token\":\"google_access_token\"}")
                .addHeader("Content-Type", "application/json"));

        mockWebServer.enqueue(new MockResponse()
                .setBody("""
                        {
                          "id": "google_67890",
                          "email": "google@test.com",
                          "name": "구글유저"
                        }
                        """)
                .addHeader("Content-Type", "application/json"));

        OAuthUserProfile profile = googleOAuthProvider.getUserProfile("auth_code");

        assertThat(profile.emailVerified()).isFalse();
    }
}
