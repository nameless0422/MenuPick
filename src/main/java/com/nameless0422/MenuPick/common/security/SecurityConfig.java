package com.nameless0422.MenuPick.common.security;

import com.nameless0422.MenuPick.domain.auth.AuthCookieProperties;
import com.nameless0422.MenuPick.domain.auth.OAuthProperties;
import com.nameless0422.MenuPick.domain.kakao.KakaoLocalProperties;
import com.nameless0422.MenuPick.domain.naver.NaverMapsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, OAuthProperties.class, NaverMapsProperties.class, KakaoLocalProperties.class, RateLimitProperties.class, AuthCookieProperties.class, CorsProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final CustomAuthenticationEntryPoint authEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final CorsProperties corsProperties;

    /**
     * 자체 계정의 비밀번호 인코더 — Argon2id.
     *
     * <p>{@link DelegatingPasswordEncoder}는 결과에 {@code {argon2}} 같은 알고리즘 접두사를 붙이고,
     * 검증 시에는 저장된 접두사를 보고 알고리즘을 고른다. 그래서 새 비밀번호는 Argon2로 만들면서
     * 과거 bcrypt 해시도 계속 검증할 수 있다. 나중에 알고리즘을 또 옮길 때도 같은 방식이다.
     *
     * <p><b>파라미터 근거</b>: OWASP가 권장하는 Argon2id 설정 중 {@code m=9216KiB, t=4, p=1}을 쓴다.
     * 같은 문서가 함께 제시하는 {@code m=19456KiB, t=2}와 강도는 동등하되 메모리를 절반 이하로 쓴다.
     * 메모리 쪽을 낮춘 이유는 운영 컨테이너가 1536MB(힙 약 1.1GB)이고 톰캣 스레드가 50개라,
     * 로그인이 몰리면 해싱 메모리가 그대로 힙 점유가 되기 때문이다. 19MiB 설정이면 최악의 경우
     * 50 × 19MiB ≈ 950MB로 힙이 통째로 잠기지만, 9MiB면 ≈450MB로 견딘다.
     * 서버 사양을 올린다면 m을 19456으로 올리는 편이 낫다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(
                16,   // salt 길이(byte)
                32,   // 해시 길이(byte)
                1,    // parallelism
                9216, // memory(KiB)
                4     // iterations
        );

        // 새 해시는 전부 argon2로 만들되, bcrypt로 저장된 기존 해시도 검증 가능하도록 남겨 둔다.
        Map<String, PasswordEncoder> encoders = Map.of(
                "argon2", argon2,
                "bcrypt", new BCryptPasswordEncoder()
        );

        return new DelegatingPasswordEncoder("argon2", encoders);
    }

    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(10));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 값이 비어 있으면(기본) 아무 오리진도 허용 안 함 — 프론트 오리진은 프로파일별로 명시해야 한다
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // Refresh Token 쿠키(HttpOnly)가 오가려면 필수 — Access-Control-Allow-Origin이 "*"가 아닌
        // 명시적 오리진이어야만 브라우저가 credentialed 요청을 허용한다
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(fl -> fl.disable())
                .httpBasic(hb -> hb.disable())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/kakao",
                                "/api/v1/auth/google",
                                "/api/v1/auth/refresh",
                                // 자체 계정 — 전부 로그인 전에 호출되는 경로다.
                                // 비밀번호 변경(PATCH /password)과 계정 조회(GET /me)는
                                // 로그인 상태에서만 쓰므로 여기 넣지 않는다.
                                "/api/v1/auth/signup",
                                "/api/v1/auth/login",
                                "/api/v1/auth/verify-email",
                                "/api/v1/auth/resend-verification",
                                "/api/v1/auth/password-reset",
                                "/api/v1/auth/password-reset/confirm").permitAll()
                        // 게스트 데모 픽 — 온보딩 퍼널용 시연 (docs/Planning.md 4.3).
                        // 고정 샘플만 반환하고 DB를 건드리지 않으며, RateLimitFilter가 IP 기준으로 제한한다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/pick/demo").permitAll()
                        // springdoc.api-docs/swagger-ui.enabled가 false면 경로 자체가 등록되지 않는다
                        // (기본 false, local 프로파일만 true — docs/ImprovementBacklog.md 7번)
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**").permitAll()
                        // 로드밸런서/오케스트레이터의 헬스체크 프로브용. show-details=never라 민감정보 노출 없음
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // /actuator/metrics는 인증만 요구 (역할 기반 접근 제어가 생기면 관리자 전용으로 좁힐 것)
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimitFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
