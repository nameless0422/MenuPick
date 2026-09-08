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
 * KPI 엔드포인트가 <b>운영자만 닿는 자리</b>에 있는지 확인한다.
 *
 * <p>{@code /actuator/kpi}는 서비스 전체 집계다 — 특정 사용자의 것이 아니라 이 서비스가
 * 얼마나 쓰이는지를 통째로 드러낸다. 이 앱에는 역할(Role) 개념이 없어서 {@code /api/v1}에
 * 두면 "인증된 아무 사용자나" 볼 수 있게 된다([D-020]의 트레이드오프 항목과 같은 함정).
 * 그래서 관리 포트에만 둔다.
 *
 * <p>여기서 중요한 단언은 "관리 포트에서 열린다"가 아니라 <b>"서비스 포트에서 닫힌다"</b>다.
 * 관리 포트는 루프백 바인딩 + publish 안 함으로 지켜지지만(D-027), 서비스 포트는
 * 리버스 프록시를 거쳐 인터넷에 닿기 때문이다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,metrics,kpi"
        })
@ActiveProfiles("integration")
class KpiEndpointIntegrationTest extends AbstractIntegrationTest {

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
    @DisplayName("KPI는 서비스 포트로 새어 나가지 않는다 — 이 테스트가 핵심")
    void kpiIsNotReachableOnTheServicePort() throws Exception {
        HttpResponse<String> response = get(servicePort, "/actuator/kpi");

        // 200이 나온다면 서비스 전체 집계가 인터넷에 닿는 포트에 올라간 것이다.
        assertThat(response.statusCode()).isNotEqualTo(200);
        assertThat(response.body())
                .doesNotContain("visitRate", "sevenDayRetention", "sameDayRepickRate");
    }

    @Test
    @DisplayName("관리 포트에서는 세 지표가 모두 나온다")
    void kpiIsServedOnTheManagementPort() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/kpi");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("visitRate")
                .contains("sameDayRepickRate")
                .contains("sevenDayRetention")
                // 비율만 있으면 "0%"와 "표본 없음"을 구분할 수 없다. 분모가 함께 나와야 한다.
                .contains("picks")
                .contains("cohort")
                .contains("target");
    }

    @Test
    @DisplayName("집계 기간을 지정할 수 있고, 터무니없는 값은 기본값으로 되돌린다")
    void lookbackDaysIsBoundedByDefault() throws Exception {
        assertThat(get(managementPort, "/actuator/kpi?lookbackDays=7").body())
                .contains("\"lookbackDays\":7");

        // 상한을 두지 않으면 전 구간 집계를 매번 새로 돌리게 된다.
        assertThat(get(managementPort, "/actuator/kpi?lookbackDays=99999").body())
                .as("범위를 벗어난 값은 조용히 받아들이지 않고 기본값 30으로 돌아간다")
                .contains("\"lookbackDays\":30");
        assertThat(get(managementPort, "/actuator/kpi?lookbackDays=0").body())
                .contains("\"lookbackDays\":30");
    }

    /**
     * 노출 목록이 관리 포트의 유일한 방어선이다(무인증이므로). {@code kpi}를 빼면
     * 클래스가 있어도 404여야 한다 — 노출은 코드가 아니라 설정의 결정이다.
     */
    @Test
    @DisplayName("노출 목록에서 빠지면 관리 포트에서도 404다")
    void kpiStaysClosedWhenNotExposed() throws Exception {
        // 이 테스트 클래스는 kpi를 노출한 컨텍스트를 쓰므로, 노출하지 않는 쪽은
        // 같은 컨텍스트에서 확인할 수 없다. 대신 화이트리스트 밖 엔드포인트가 닫혀 있음을
        // 확인해 "노출 목록이 실제로 작동한다"는 전제를 고정한다.
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get(managementPort, "/actuator/beans").statusCode()).isEqualTo(404);
    }
}
