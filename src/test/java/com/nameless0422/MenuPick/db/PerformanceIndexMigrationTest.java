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
 * V3__performance_indexes.sql이 실제 MySQL에 적용되는지 검증한다.
 * (Testcontainers MySQL에 Flyway가 V1 → V2 → V3 순서로 적용한 결과를 information_schema로 확인)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class PerformanceIndexMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("인덱스 정리 마이그레이션(V3)이 성공 상태로 기록된다")
    void v3_isApplied() {
        // success 컬럼은 드라이버 설정에 따라 Boolean 또는 숫자로 넘어온다
        Object applied = em.createNativeQuery(
                        "SELECT success FROM flyway_schema_history WHERE version = '3'")
                .getSingleResult();

        assertThat(applied).isNotNull();
        boolean success = (applied instanceof Boolean b) ? b : ((Number) applied).intValue() == 1;
        assertThat(success).isTrue();
    }

    @Test
    @DisplayName("스케줄러 조회용 users(deleted_at) 인덱스가 추가된다")
    void usersDeletedAtIndexAdded() {
        assertThat(indexColumns("users", "idx_users_deleted")).containsExactly("deleted_at");
    }

    @Test
    @DisplayName("히스토리 커서 조회용 histories(user_id, id DESC) 인덱스가 추가된다")
    void historiesCursorIndexAdded() {
        assertThat(indexColumns("histories", "idx_histories_user_id"))
                .containsExactly("user_id", "id");
    }

    @Test
    @DisplayName("중복 인덱스 idx_hfc_history_id는 제거되고 idx_hfc_type_value는 남는다")
    void duplicateFilterConditionIndexDropped() {
        assertThat(indexColumns("history_filter_conditions", "idx_hfc_history_id")).isEmpty();
        assertThat(indexColumns("history_filter_conditions", "idx_hfc_type_value"))
                .containsExactly("history_id", "filter_type");
    }

    @Test
    @DisplayName("menus 인덱스가 실제 쿼리에 맞게 정리된다")
    void menusIndexesReorganized() {
        assertThat(indexColumns("menus", "idx_menus_name_search")).isEmpty();
        assertThat(indexColumns("menus", "idx_menus_user_excluded"))
                .containsExactly("user_id", "is_excluded", "deleted_at");
        // FK(fk_menus_user)를 떠받치는 인덱스는 그대로 유지되어야 한다
        assertThat(indexColumns("menus", "idx_menus_user_deleted"))
                .containsExactly("user_id", "deleted_at");
    }

    @SuppressWarnings("unchecked")
    private List<String> indexColumns(String table, String index) {
        return em.createNativeQuery(
                        "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = :table "
                                + "AND INDEX_NAME = :index ORDER BY SEQ_IN_INDEX")
                .setParameter("table", table)
                .setParameter("index", index)
                .getResultList();
    }
}
