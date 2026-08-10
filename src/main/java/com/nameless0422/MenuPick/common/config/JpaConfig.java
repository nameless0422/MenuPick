package com.nameless0422.MenuPick.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * JPA 설정.
 *
 * <p>{@code @EnableJpaAuditing}을 별도 클래스에 분리하여 {@code @WebMvcTest} 슬라이스 테스트 시
 * JPA 컨텍스트 없이도 로딩 오류가 발생하지 않도록 한다.
 *
 * <p>Auditing(createdAt/updatedAt)도 도메인 코드와 같은 {@link Clock}을 쓰도록
 * {@link DateTimeProvider}를 등록한다. {@code @DataJpaTest} 슬라이스는 이 설정만
 * {@code @Import} 하므로 {@link TimeConfig}를 함께 끌어온다.
 */
@Configuration
@Import(TimeConfig.class)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
@EnableScheduling
public class JpaConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(LocalDateTime.now(clock));
    }
}
