package com.nameless0422.MenuPick.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * H2와 운영 MySQL 간 스키마/방언 드리프트를 막기 위해, 실 MySQL 컨테이너 위에서
 * Flyway 마이그레이션을 적용한 채로 돌리는 통합 테스트 베이스. {@code integration}
 * 프로파일과 함께 사용한다 (docs/ImprovementBacklog.md 6번).
 *
 * <p>싱글턴 컨테이너 패턴: {@code @Testcontainers}+{@code @Container}는 클래스가 끝날 때
 * 컨테이너를 정지시키는데, Spring 테스트 컨텍스트는 클래스 간 캐시되므로 뒤 클래스가
 * 죽은 포트의 캐시된 DataSource로 접속해 실패한다. JVM당 1회만 기동하고 종료는
 * Ryuk에 맡긴다.
 */
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("menupick")
            .withUsername("menupick")
            .withPassword("menupick");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
