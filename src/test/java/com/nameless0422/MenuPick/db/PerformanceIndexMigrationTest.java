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
 * 인덱스 관련 마이그레이션(V3, V6)이 실제 MySQL에 적용되는지 검증한다.
 * (Testcontainers MySQL에 Flyway가 순서대로 적용한 결과를 information_schema로 확인)
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

    @Test
    @DisplayName("미사용 인덱스 정리 마이그레이션(V6)이 성공 상태로 기록된다")
    void v6_isApplied() {
        Object applied = em.createNativeQuery(
                        "SELECT success FROM flyway_schema_history WHERE version = '6'")
                .getSingleResult();

        assertThat(applied).isNotNull();
        boolean success = (applied instanceof Boolean b) ? b : ((Number) applied).intValue() == 1;
        assertThat(success).isTrue();
    }

    @Test
    @DisplayName("어떤 쿼리도 쓰지 않던 인덱스 둘이 제거된다")
    void unusedIndexesDropped() {
        // is_visited를 조건으로 쓰는 쿼리가 HistoryRepository에 없다
        assertThat(indexColumns("histories", "idx_histories_visited")).isEmpty();
        // category 단독 조회 경로가 없다
        assertThat(indexColumns("menu_categories", "idx_menu_categories_cat")).isEmpty();
    }

    @Test
    @DisplayName("드롭한 인덱스가 떠받치던 제약과 커서 인덱스는 그대로 남는다")
    void constraintsSurviveTheDrop() {
        // menu_categories의 FK는 PK 접두사(menu_id)가 만족시킨다 — 인덱스를 지워도 제약이 깨지지 않는다
        assertThat(indexColumns("menu_categories", "PRIMARY")).startsWith("menu_id");
        // V3이 정렬을 인덱스에 맡기려고 만든 커서 인덱스는 이번 정리 대상이 아니다
        assertThat(indexColumns("histories", "idx_histories_user_id"))
                .containsExactly("user_id", "id");
    }

    @Test
    @DisplayName("거리 필터를 DB로 내릴 때 쓸 restaurants(위경도) 인덱스는 남겨 둔다")
    void locationIndexKept() {
        // 지금은 쓰는 쿼리가 없지만 restaurants는 쓰기 볼륨이 낮고, 픽의 거리 필터를
        // 바운딩 박스로 내리는 과제(#87)가 이 인덱스를 그대로 쓴다.
        assertThat(indexColumns("restaurants", "idx_restaurants_location"))
                .containsExactly("user_id", "latitude", "longitude");
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
