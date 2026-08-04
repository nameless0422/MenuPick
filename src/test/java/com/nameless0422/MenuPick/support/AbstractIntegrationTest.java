package com.nameless0422.MenuPick.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * H2와 운영 MySQL 간 스키마/방언 드리프트를 막기 위해, 실 MySQL 컨테이너 위에서
 * Flyway 마이그레이션을 적용한 채로 돌리는 통합 테스트 베이스. {@code integration}
 * 프로파일과 함께 사용한다 (docs/ImprovementBacklog.md 6번).
 */
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("menupick")
            .withUsername("menupick")
            .withPassword("menupick");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
