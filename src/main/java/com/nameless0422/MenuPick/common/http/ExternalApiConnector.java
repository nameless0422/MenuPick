package com.nameless0422.MenuPick.common.http;

import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * 외부 지도 API 전용 HTTP 커넥터 팩토리.
 *
 * <p><b>왜 업스트림마다 별도 풀이 필요한가.</b> {@code HttpClient.create()}는 전역
 * {@code HttpResources} 풀을 쓴다. 공용 {@code WebClient} 빈(OAuth 토큰 교환)도 같은 팩토리로
 * 만들어져 있어, 카카오 장소 검색·네이버 지오코딩·OAuth 토큰 교환이 <b>커넥션 풀 하나를
 * 공유</b>했다. 카카오가 응답만 느려지는 부분 장애가 나면 톰캣 스레드가 전부 {@code block()}에
 * 잠기고, 같은 풀을 쓰는 OAuth 토큰 교환이 커넥션을 얻지 못해 <b>로그인까지 함께 멈춘다.</b>
 * 업스트림별 전용 풀은 그 전파를 끊는 격벽이다 — 카카오가 죽어도 네이버와 로그인은 자기 풀을
 * 그대로 쓴다.
 *
 * <p><b>지연 상한.</b> 아래 네 값이 한 번의 시도에 순서대로 더해진다. 전역 기본값을 그대로 쓰면
 * 각각 45초(풀 대기)와 10초(TLS 핸드셰이크)라, 사용자가 화면 앞에서 기다리는 동기 호출에는
 * 터무니없이 관대하다.
 *
 * <pre>
 *   풀 대기        0.5s   (전역 기본값 45s)
 *   connect        2.0s
 *   TLS 핸드셰이크  2.0s   (reactor-netty 기본값 10s — responseTimeout에 포함되지 않는다)
 *   응답           3.0s
 *   ─────────────────────
 *   시도 1회        7.5s
 *   재시도 1회 포함  7.5 + 0.2(재시도 대기) + 7.5 = 15.2s
 * </pre>
 *
 * <p>이 값들이 전부 최악으로 소진되는 경우(두 시도 모두 새 커넥션 + 매 단계 만료)는 현실적으로
 * 드물지만, 상한이 존재한다는 것 자체가 요점이다. 호출부는 여기에 더해 {@link #TOTAL_TIMEOUT}으로
 * 전체 왕복에 하드 데드라인을 건다 — 재시도까지 합친 시간이 프론트엔드 axios의 15초 타임아웃을
 * 넘어서지 않게 하기 위해서다.
 *
 * <p>풀은 프로세스 수명 동안 살아 있는 싱글턴이라 별도로 정리하지 않는다(전역 {@code HttpResources}와
 * 같은 성질이다).
 */
public final class ExternalApiConnector {

    /** TCP 연결 수립 상한. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 요청 write 이후 응답까지의 상한. 핸드셰이크는 여기 포함되지 않으므로 따로 건다.
     */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    /**
     * TLS 핸드셰이크 상한. reactor-netty 기본값은 10초다 — 명시하지 않으면 커넥션 하나가
     * 그 10초 동안 톰캣 스레드를 붙잡는다.
     */
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(2);

    /**
     * 풀에 빈 커넥션이 없을 때 기다리는 상한. 기본값 45초를 그대로 두면 풀이 마른 순간
     * 시도당 45초가 얹혀 한 요청이 2분 가까이 스레드를 점유한다. 짧게 끊어 빠르게 실패시킨다 —
     * 이 경로의 실패는 "지도 검색이 안 된다"이고, 그 대가로 로그인이 멈추는 편이 훨씬 나쁘다.
     */
    private static final Duration PENDING_ACQUIRE_TIMEOUT = Duration.ofMillis(500);

    /**
     * 업스트림당 동시 커넥션 상한. 전역 기본값은 {@code max(cores, 8) * 2}(소형 VM에서 16)이고
     * 그것을 세 용도가 나눠 썼다. 8이면 톰캣 50스레드 중 최대 8개만 이 업스트림에 묶인다.
     */
    private static final int MAX_CONNECTIONS = 8;

    /**
     * 재시도까지 포함한 전체 왕복의 하드 데드라인. 단계별 상한의 합(15.2s)은 프론트엔드
     * axios 타임아웃(15s)을 넘어서므로, 그 전에 우리 쪽에서 끊어 사용자에게 한국어 오류를
     * 돌려준다. 이 값을 넘긴 호출은 성공해도 이미 쓸모가 없다.
     */
    public static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(10);

    private ExternalApiConnector() {}

    /**
     * 이름이 붙은 전용 커넥션 풀 위에 커넥터를 만든다.
     *
     * @param poolName 메트릭·로그에 나타나는 풀 이름 (업스트림당 하나)
     */
    public static ReactorClientHttpConnector isolated(String poolName) {
        ConnectionProvider provider = ConnectionProvider.builder(poolName)
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireTimeout(PENDING_ACQUIRE_TIMEOUT)
                .build();

        return new ReactorClientHttpConnector(
                HttpClient.create(provider)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                        .responseTimeout(RESPONSE_TIMEOUT)
                        // handshakeTimeout은 SslProvider.Builder에 있어 sslContext를 먼저 지정해야 한다.
                        // 기본 클라이언트 컨텍스트를 그대로 쓰고 상한만 얹는다. http:// 대상에는
                        // reactor-netty가 SSL을 적용하지 않으므로 테스트의 MockWebServer에도 영향이 없다.
                        .secure(spec -> spec.sslContext(Http11SslContextSpec.forClient())
                                .handshakeTimeout(HANDSHAKE_TIMEOUT)));
    }
}
