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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본 메뉴의 기준 음식 연결(V28)을 실제 MySQL에서 검증한다. Flyway가 적용할 때는 메뉴도 기준 음식도 없어
 * 아무 행도 바뀌지 않으므로, 둘을 만들어 두고 같은 UPDATE를 한 번 더 돌린다(V27 테스트와 같은 방식).
 * 열을 더하는 ALTER는 이미 적용됐으니 건너뛴다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class DefaultMenuFoodLinkMigrationTest extends AbstractIntegrationTest {

    private static final Path SCRIPT =
            Path.of("src/main/resources/db/migration/V28__food_reference_unit_and_default_links.sql");

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("V27 추정치 그대로인 기본 메뉴만 기준 음식에 연결하고, 고친 메뉴와 데이터가 없는 메뉴는 그대로 둔다")
    void linksOnlyUntouchedDefaultMenus() throws IOException {
        long referenceId = insertReference("D306-266000000-0001", "김치찌개", 244);
        long userId = createUser();
        long untouched = insertMenu(userId, "김치찌개", 230, "10.0", "16.0", "14.0", "3.0", "4.0", 1900);
        long edited = insertMenu(userId, "김치찌개", 230, "10.0", "16.0", "14.0", "3.0", "4.0", 1500);
        long noData = insertMenu(userId, "된장찌개", 150, "12.0", "12.0", "6.0", "3.5", "3.0", 1800);

        runUpdate();
        runUpdate(); // 다시 돌려도 이미 연결한 행은 대상에서 빠진다

        Object[] linked = row(untouched);
        assertThat(((Number) linked[0]).longValue()).isEqualTo(referenceId);
        assertThat(((Number) linked[1]).intValue()).isEqualTo(244);
        assertThat(linked[2].toString()).isEqualTo("11.9");
        assertThat(((Number) linked[3]).intValue()).isEqualTo(1964);
        assertThat(((Number) linked[4]).longValue()).as("version").isEqualTo(1L);

        Object[] kept = row(edited);
        assertThat(kept[0]).as("나트륨을 고친 메뉴").isNull();
        assertThat(((Number) kept[1]).intValue()).isEqualTo(230);
        assertThat(((Number) kept[4]).longValue()).isZero();

        assertThat(row(noData)[0]).as("기준 음식 행이 없는 메뉴").isNull();
    }

    @Test
    @DisplayName("기준 음식에 1인분 단위 열이 생기고 기본값은 g이다")
    void addsServingUnitDefaultingToGrams() {
        long id = insertReference("D999-000000000-0001", "단위확인", 100);
        Object unit = em.createNativeQuery("SELECT serving_unit FROM food_references WHERE id = :id")
                .setParameter("id", id).getSingleResult();
        assertThat(unit).isEqualTo("g");
    }

    private Object[] row(long menuId) {
        return (Object[]) em.createNativeQuery("""
                        SELECT food_reference_id, kcal_per_serving, carbs_g, sodium_mg, version
                        FROM menus WHERE id = :id
                        """)
                .setParameter("id", menuId)
                .getSingleResult();
    }

    private void runUpdate() throws IOException {
        String sql = Files.readString(SCRIPT, StandardCharsets.UTF_8).lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);
        Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .filter(statement -> !statement.startsWith("ALTER TABLE"))
                .forEach(statement -> em.createNativeQuery(statement).executeUpdate());
    }

    private long insertReference(String code, String name, int kcal) {
        em.createNativeQuery("""
                        INSERT INTO food_references (code, name, category, serving_grams, kcal_per_serving,
                            carbs_g, protein_g, fat_g, fiber_g, sugars_g, sodium_mg, imported_at)
                        VALUES (:code, :name, '찌개', 400.0, :kcal, 11.9, 15.1, 15.0, NULL, NULL, 1964, NOW(6))
                        """)
                .setParameter("code", code)
                .setParameter("name", name)
                .setParameter("kcal", kcal)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM food_references WHERE code = :code")
                .setParameter("code", code).getSingleResult()).longValue();
    }

    private long createUser() {
        em.createNativeQuery("""
                        INSERT INTO users (email, email_verified, nickname)
                        VALUES ('v28-food-link@example.com', 1, 'v28연결')
                        """)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE nickname = 'v28연결'")
                .getSingleResult()).longValue();
    }

    private long insertMenu(long userId, String name, int kcal, String carbs, String protein, String fat,
                            String fiber, String sugars, int sodium) {
        em.createNativeQuery("""
                        INSERT INTO menus (user_id, name, weight, is_excluded, kcal_per_serving,
                            carbs_g, protein_g, fat_g, fiber_g, sugars_g, sodium_mg)
                        VALUES (:userId, :name, 1, 0, :kcal, :carbs, :protein, :fat, :fiber, :sugars, :sodium)
                        """)
                .setParameter("userId", userId)
                .setParameter("name", name)
                .setParameter("kcal", kcal)
                .setParameter("carbs", new java.math.BigDecimal(carbs))
                .setParameter("protein", new java.math.BigDecimal(protein))
                .setParameter("fat", new java.math.BigDecimal(fat))
                .setParameter("fiber", new java.math.BigDecimal(fiber))
                .setParameter("sugars", new java.math.BigDecimal(sugars))
                .setParameter("sodium", sodium)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM menus WHERE user_id = :userId AND name = :name")
                .setParameter("userId", userId)
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
