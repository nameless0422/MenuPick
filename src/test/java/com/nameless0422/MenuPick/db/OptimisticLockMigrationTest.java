package com.nameless0422.MenuPick.db;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 낙관적 락 버전 컬럼 마이그레이션(V9)이 실제 MySQL에 적용되는지 검증한다.
 * (Testcontainers MySQL에 Flyway가 순서대로 적용한 결과를 information_schema로 확인)
 *
 * <p>동작 자체는 {@code domain/OptimisticLockTest}가 본다. 여기서 따로 확인하는 것은
 * <b>기존 행이 있는 DB에서도 이 마이그레이션이 통과하는가</b>이다. {@code ddl-auto: validate}
 * 아래에서 엔티티에 {@code @Version}만 추가하고 컬럼을 빠뜨리면 앱이 기동조차 못 하고,
 * 반대로 컬럼에 기본값이 없으면 이미 데이터가 있는 운영 DB에서 ALTER 자체가 실패한다.
 * 둘 다 배포 순간에야 드러나는 종류라 미리 못 박아 둔다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class OptimisticLockMigrationTest extends AbstractIntegrationTest {

    /** V9가 버전 컬럼을 넣는 테이블. 근거는 V9 주석(왜 이 셋인지, 왜 나머지가 아닌지). */
    private static final List<String> VERSIONED_TABLES =
            List.of("menus", "restaurants", "menu_restaurants");

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("낙관적 락 마이그레이션(V9)이 성공 상태로 기록된다")
    void v9_isApplied() {
        // success 컬럼은 드라이버 설정에 따라 Boolean 또는 숫자로 넘어온다
        Object applied = em.createNativeQuery(
                        "SELECT success FROM flyway_schema_history WHERE version = '9'")
                .getSingleResult();

        assertThat(applied).isNotNull();
        boolean success = (applied instanceof Boolean b) ? b : ((Number) applied).intValue() == 1;
        assertThat(success).isTrue();
    }

    @Test
    @DisplayName("세 테이블에 NOT NULL + 기본값 0인 version 컬럼이 생긴다")
    void versionColumnsExist() {
        for (String table : VERSIONED_TABLES) {
            List<?> rows = em.createNativeQuery("""
                            SELECT is_nullable, column_default
                            FROM information_schema.columns
                            WHERE table_schema = DATABASE()
                              AND table_name = :table
                              AND column_name = 'version'
                            """)
                    .setParameter("table", table)
                    .getResultList();

            assertThat(rows).as("%s.version 컬럼", table).hasSize(1);

            Object[] row = (Object[]) rows.get(0);
            // NOT NULL이라야 Hibernate가 null 버전을 "저장된 적 없는 엔티티"로 오인할 여지가 없다.
            assertThat(row[0]).as("%s.version is_nullable", table).isEqualTo("NO");
            // 기본값이 없으면 이미 행이 있는 DB에서 ALTER가 실패한다.
            assertThat(String.valueOf(row[1])).as("%s.version 기본값", table).isEqualTo("0");
        }
    }

    @Test
    @DisplayName("버전 컬럼을 넣지 않기로 한 테이블에는 생기지 않는다")
    void unversionedTablesAreUntouched() {
        // 근거는 V9 주석. histories는 쓰기가 단조 전이라 잃을 앞선 변경이 없고, tags와
        // history_filter_conditions는 수정 엔드포인트 자체가 없다. "일단 전부 붙이자"로
        // 번지면 histories처럼 쓰기가 잦은 테이블에서 이유 없는 409가 나기 시작한다.
        for (String table : List.of("histories", "tags", "history_filter_conditions", "users")) {
            Number count = (Number) em.createNativeQuery("""
                            SELECT COUNT(*) FROM information_schema.columns
                            WHERE table_schema = DATABASE()
                              AND table_name = :table
                              AND column_name = 'version'
                            """)
                    .setParameter("table", table)
                    .getSingleResult();

            assertThat(count.intValue()).as("%s.version 컬럼", table).isZero();
        }
    }
}
