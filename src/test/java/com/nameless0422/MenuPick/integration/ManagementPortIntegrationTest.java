package com.nameless0422.MenuPick.integration;

import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영에서 actuator를 서비스 포트에서 떼어내는 구성({@code management.server.port})이
 * 실제로 무엇을 바꾸는지 검증한다.
 *
 * <p>이 설정의 값어치는 딱 하나다 — <b>인터넷에 닿는 포트에서 actuator가 사라지는 것</b>.
 * metrics에는 커넥션 풀 점유, 요청 경로별 호출량, 힙 사용량이 그대로 들어 있어 서비스 포트에
 * 있으면 내부 구조가 읽힌다. 그래서 "관리 포트에서 열린다"보다 "서비스 포트에서 닫힌다" 쪽이
 * 더 중요한 단언이고, 앞의 두 테스트가 그것이다.
 *
 * <p>관리 포트가 <b>무인증</b>이라는 사실도 함께 못 박는다. 시큐리티 체인은 관리 포트에도 걸리지만
 * {@code SecurityConfig}가 그 포트를 permitAll로 뺀다 — 운영자가 Access Token을 들고 다닐 수 없기
 * 때문이다(D-026). 즉 metrics를 지키는 건 인증이 아니라 "루프백 바인딩 + publish 안 함"이다.
 * 이 전제가 무너지면(예: 누군가 포트를 publish하면) 곧장 노출이므로 테스트로 남긴다.
 *
 * <p>포트를 실제로 두 개 열어야 확인되는 성질이라 {@code RANDOM_PORT}로 서버를 띄운다.
 * 관리 포트도 0을 줘서 CI에서 고정 포트 충돌이 나지 않게 한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,metrics"
        })
@ActiveProfiles("integration")
class ManagementPortIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort private int servicePort;
    @LocalManagementPort private int managementPort;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("서비스 포트에는 actuator가 매핑조차 되지 않는다 — 이 설정의 핵심")
    void actuatorIsNotMappedOnTheServicePort() throws Exception {
        // health는 permitAll이라 시큐리티를 통과한다. 그런데도 404라면 핸들러가 없다는 뜻 —
        // actuator 전체가 이 포트에 없다는 구조적 증거다(metrics도 같은 관리 컨텍스트에 있다).
        assertThat(get(servicePort, "/actuator/health").statusCode())
                .as("여기서 200이 나오면 actuator가 서비스 포트에 남아 있는 것")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("서비스 포트로 metrics를 부르면 지표가 한 줄도 나오지 않는다")
    void metricsLeakNothingOnTheServicePort() throws Exception {
        HttpResponse<String> response = get(servicePort, "/actuator/metrics");

        // 404가 아니라 401인 이유: 매핑이 없어도 anyRequest().authenticated()가 먼저 잘라낸다.
        // 상태 코드만으로는 "없다"와 "인증이 필요하다"를 구분할 수 없으므로 본문까지 확인한다.
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body())
                .contains("UNAUTHORIZED")
                .doesNotContain("hikaricp", "jvm.memory");
    }

    @Test
    @DisplayName("관리 포트는 인증 없이 읽힌다 — 그래서 절대 publish하면 안 된다")
    void managementPortRequiresNoAuthentication() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/metrics");

        assertThat(response.statusCode())
                .as("401이 나온다면 운영자가 metrics를 볼 방법이 없어진다 — "
                        + "Access Token은 브라우저 메모리에만 있고 30분이면 만료된다(D-026)")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("Dockerfile HEALTHCHECK가 보는 health는 관리 포트에 있다")
    void healthMovesToTheManagementPort() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(response.body())
                .as("show-details: never — 무인증으로 닿는 엔드포인트에 DB/Redis 상세를 실으면 안 된다")
                .doesNotContain("components");
    }

    @Test
    @DisplayName("노출 목록에 없는 엔드포인트는 관리 포트에서도 닫혀 있다")
    void unexposedEndpointsStayClosed() throws Exception {
        // 관리 포트가 무인증이므로, 노출 화이트리스트가 유일한 방어선이다.
        // env는 시크릿(DB 비밀번호·JWT 시크릿)이 통째로 들어 있는 엔드포인트다.
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get(managementPort, "/actuator/configprops").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("운영에서 실제로 보려던 지표들이 관리 포트에 있다")
    void exposesTheMetricsThisAppActuallyNeeds() throws Exception {
        String names = get(managementPort, "/actuator/metrics").body();

        assertThat(names)
                .as("커넥션 풀(10) 고갈 — 톰캣 스레드가 DB 대기로 잠기는 신호")
                .contains("hikaricp.connections.pending");
        assertThat(names)
                .as("메일 큐(100) 적체 — 차면 CallerRunsPolicy가 발송을 요청 스레드로 되돌린다")
                .contains("executor.queued");
        assertThat(names)
                .as("mem_limit의 75%가 힙 상한")
                .contains("jvm.memory.used");
    }

    @Test
    @DisplayName("메일 실행기 큐 깊이를 이름으로 집어서 볼 수 있다")
    void mailExecutorQueueDepthIsQueryable() throws Exception {
        HttpResponse<String> response =
                get(managementPort, "/actuator/metrics/executor.queued?tag=name:mailTaskExecutor");

        assertThat(response.statusCode())
                .as("태그가 안 붙으면 여러 실행기가 한 값으로 뭉쳐 메일 적체를 따로 볼 수 없다")
                .isEqualTo(200);
        assertThat(response.body()).contains("VALUE");
    }
}
