package com.nameless0422.MenuPick.integration;

import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,trends",
                "trends.enabled=false"
        })
@ActiveProfiles("integration")
class PickTrendEndpointIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort private int servicePort;
    @LocalManagementPort private int managementPort;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> request(int port, String path, String method) throws Exception {
        return client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> authenticatedPost(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(1L))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("GET trends는 서비스 포트에서도 인증 없이는 거부한다")
    void trendsGetRequiresAuthentication() throws Exception {
        HttpResponse<String> response = request(servicePort, "/api/v1/trends", "GET");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("UNAUTHORIZED");
    }

    @Test
    @DisplayName("수동 재집계는 관리 포트의 POST로만 호출할 수 있다")
    void recomputeIsOnlyAvailableOnManagementPort() throws Exception {
        // Authentication must pass first; otherwise Security's 401 would hide whether an actuator
        // handler accidentally exists on the service port.
        HttpResponse<String> service = authenticatedPost(servicePort, "/actuator/trends");
        HttpResponse<String> management = request(managementPort, "/actuator/trends", "POST");

        assertThat(service.statusCode())
                .as("actuator handler must not be mapped on the internet-facing service port")
                .isEqualTo(404);
        assertThat(service.body()).doesNotContain("categoryCount", "menuCount", "computedAt");
        assertThat(management.statusCode()).isEqualTo(200);
        assertThat(management.body())
                .contains("\"enabled\":false")
                .contains("\"categoryCount\":0")
                .contains("\"menuCount\":0");
    }

    @Test
    @DisplayName("관리 엔드포인트의 GET은 재집계를 실행하지 않는다")
    void recomputeRejectsGet() throws Exception {
        assertThat(request(managementPort, "/actuator/trends", "GET").statusCode())
                .isEqualTo(405);
    }
}
