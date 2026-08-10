package com.nameless0422.MenuPick.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 시각 소스 설정.
 *
 * <p>서비스 사용자층이 한국으로 고정되어 있어 <b>애플리케이션 기준 시간대를 KST로 고정</b>한다.
 * 서버 JVM의 기본 시간대(컨테이너/호스트 설정에 따라 UTC일 수 있음)에 결과가 좌우되지 않도록,
 * 도메인 코드는 {@code LocalDateTime.now()} 대신 이 {@link Clock}을 주입받아
 * {@code LocalDateTime.now(clock)}으로 현재 시각을 얻는다.
 *
 * <p>DB 스키마는 기존대로 {@code DATETIME}(=LocalDateTime) 을 유지한다. UTC 저장 + 표시 변환으로
 * 바꾸는 것은 스키마·API 계약을 모두 흔들기 때문에 이번 범위에서 제외한 설계 결정이다.
 */
@Configuration
public class TimeConfig {

    /** 서비스 기준 시간대. 엔티티처럼 빈 주입이 불가능한 지점에서만 직접 참조한다. */
    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(SERVICE_ZONE);
    }
}
