package com.nameless0422.MenuPick.common.logging;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * {@link TraceIdFilter}를 서블릿 필터 체인 최바깥에 등록한다.
 *
 * <p>Spring Security의 필터 체인(DelegatingFilterProxy)은 order -100에 등록되므로,
 * {@code HIGHEST_PRECEDENCE}로 그보다 앞에 두어야 인증 실패(401)나 레이트 리밋(429)처럼
 * 시큐리티 체인 안에서 끝나버리는 요청의 로그까지 같은 traceId로 묶인다.
 *
 * <p>필터 인스턴스를 빈으로 노출하지 않고 여기서 직접 생성하는 이유는 {@link TraceIdFilter}
 * javadoc 참고 — 빈이 되면 Boot의 서블릿 필터 자동 등록이 겹쳐 등록 경로가 둘로 갈린다.
 */
@Configuration
public class TraceIdFilterConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration =
                new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setName("traceIdFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * 접근 로그를 {@link TraceIdFilter} 바로 안쪽에 등록한다.
     *
     * <p>바깥이면 traceId가 아직 MDC에 없어 접근 로그만 상관관계에서 떨어져 나가고,
     * 시큐리티 체인(order -100)보다는 바깥이어야 401·429도 기록에 남는다.
     * 등록 방식이 같은 이유는 {@link TraceIdFilter} javadoc 참고 — 필터를 빈으로 두면
     * Boot의 자동 등록이 겹쳐 순서를 못 박을 수 없다.
     */
    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration() {
        FilterRegistrationBean<AccessLogFilter> registration =
                new FilterRegistrationBean<>(new AccessLogFilter());
        registration.setName("accessLogFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
