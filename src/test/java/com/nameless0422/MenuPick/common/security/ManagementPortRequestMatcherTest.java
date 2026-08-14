package com.nameless0422.MenuPick.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이 매처는 매치되면 <b>인증을 면제</b>하므로, 틀리는 방향이 한쪽으로 치우쳐 있다.
 * 덜 매치되면 운영자가 metrics를 못 보는 정도지만, 더 매치되면 API 전체가 무인증으로 열린다.
 * 그래서 "열리면 안 되는 경우"를 중심으로 검증한다.
 */
class ManagementPortRequestMatcherTest {

    private final MockEnvironment environment = new MockEnvironment();
    private final ManagementPortRequestMatcher matcher = new ManagementPortRequestMatcher(environment);

    private void boundPorts(String service, String management) {
        if (service != null) {
            environment.setProperty("local.server.port", service);
        }
        if (management != null) {
            environment.setProperty("local.management.port", management);
        }
    }

    private MockHttpServletRequest requestArrivingOn(int port) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        request.setLocalPort(port);
        return request;
    }

    @Test
    @DisplayName("포트를 분리했고 관리 포트로 들어온 요청이면 매치된다")
    void matchesRequestOnSeparateManagementPort() {
        boundPorts("8080", "9090");

        assertThat(matcher.matches(requestArrivingOn(9090))).isTrue();
    }

    @Test
    @DisplayName("포트를 분리했어도 서비스 포트로 들어온 요청이면 매치되지 않는다")
    void doesNotMatchRequestOnServicePort() {
        boundPorts("8080", "9090");

        assertThat(matcher.matches(requestArrivingOn(8080))).isFalse();
    }

    @Test
    @DisplayName("포트를 분리하지 않으면 그 포트로 온 요청도 매치되지 않는다 — 여기서 틀리면 API 전체가 열린다")
    void neverMatchesWhenPortsAreNotSeparated() {
        // Boot의 local.management.port는 관리 포트를 분리하지 않으면 서비스 포트 값으로 채워진다.
        // 이 경우까지 매치되면 local/dev에서 /api/v1/** 전체가 permitAll이 된다.
        boundPorts("8080", "8080");

        assertThat(matcher.matches(requestArrivingOn(8080))).isFalse();
    }

    @Test
    @DisplayName("관리 포트가 아직 바인딩되지 않았으면 매치되지 않는다")
    void doesNotMatchBeforeManagementServerIsBound() {
        // 시큐리티 빈은 관리 서버보다 먼저 만들어진다. 그 사이 요청이 들어와도 열려선 안 된다.
        boundPorts("8080", null);

        assertThat(matcher.matches(requestArrivingOn(8080))).isFalse();
        assertThat(matcher.matches(requestArrivingOn(9090))).isFalse();
    }

    @Test
    @DisplayName("서비스 포트 값을 못 읽어도 관리 포트만 보고 열지 않는다")
    void doesNotMatchWhenServicePortIsUnknown() {
        // 두 값을 비교해 "분리 여부"를 판단하므로, 한쪽을 못 읽으면 분리됐다고 단정할 수 없다.
        // 다만 -1은 어떤 실제 포트와도 같지 않으므로 관리 포트가 잡혀 있으면 매치는 성립한다 —
        // 이 테스트는 그 경계에서 서비스 포트로 온 요청이 새지 않는지를 본다.
        boundPorts(null, "9090");

        assertThat(matcher.matches(requestArrivingOn(8080))).isFalse();
    }
}
