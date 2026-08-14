package com.nameless0422.MenuPick.common.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 요청이 <b>서비스 포트와 분리된 관리 포트</b>로 들어왔는지 판별한다.
 *
 * <p>{@code management.server.port}를 서비스 포트와 다르게 주면(운영 구성) Boot는 actuator만 서비스하는
 * 별도 포트를 연다. 그 포트는 컨테이너 루프백에만 바인딩되고 compose에서 publish하지 않으므로,
 * 거기 닿을 수 있는 주체는 이미 컨테이너 안에 있는 사람 — 즉 운영자뿐이다. 그래서 그 포트에 한해
 * 인증을 면제한다. 이게 없으면 metrics를 보려면 Access Token이 필요한데, Access Token은 설계상
 * 브라우저 메모리에만 있고(docs/DecisionLog.md D-026) 30분이면 만료돼 사실상 꺼내 쓸 수 없다.
 *
 * <p><b>이 판별이 왜 두 포트를 모두 보는가</b>: Boot의 {@code local.management.port}는 관리 포트를
 * 분리하지 <b>않았을 때 서비스 포트 값으로 채워진다</b>. 그래서 "관리 포트와 같은가"만 보면
 * local/dev처럼 분리하지 않은 구성에서 <b>모든 요청</b>이 인증 면제 대상이 된다. 두 값이 같으면
 * 분리되지 않은 것으로 보고 무조건 false를 돌려주는 이유다.
 *
 * <p>설정값({@code management.server.port})이 아니라 실제로 바인딩된 포트를 쓴다 — 0(임의 포트)으로
 * 띄우는 경우 설정값과 실제 포트가 다르다.
 *
 * <p>매 요청 조회하지만 {@link Environment} 조회는 맵 탐색 몇 번이라, 같은 체인에서 도는 JWT 파싱에
 * 비하면 무시할 수준이다. 캐시하지 않는 편이 기동 순서(관리 서버는 시큐리티 빈보다 늦게 뜬다)에
 * 얽히지 않아 단순하다.
 */
@RequiredArgsConstructor
public class ManagementPortRequestMatcher implements RequestMatcher {

    private final Environment environment;

    @Override
    public boolean matches(HttpServletRequest request) {
        int managementPort = boundPort("local.management.port");
        if (managementPort <= 0) {
            return false;
        }
        if (managementPort == boundPort("local.server.port")) {
            // 관리 포트를 분리하지 않은 구성. 여기서 true를 주면 서비스 포트 전체가 열린다.
            return false;
        }
        return request.getLocalPort() == managementPort;
    }

    private int boundPort(String property) {
        return environment.getProperty(property, Integer.class, -1);
    }
}
