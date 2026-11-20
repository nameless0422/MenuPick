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
 * 기본 메뉴 영양소 백필(V27)을 실제 MySQL에서 검증한다. 테스트 DB에 Flyway가 적용할 때는 메뉴가
 * 없어 아무 행도 바뀌지 않으므로, 메뉴를 만들어 두고 같은 SQL을 한 번 더 돌린다(V10 테스트와 같은 방식).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class DefaultMenuNutritionMigrationTest extends AbstractIntegrationTest {

    private static final Path SCRIPT =
            Path.of("src/main/resources/db/migration/V27__default_menu_nutrition.sql");

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("열량이 비어 있는 기본 메뉴만 채우고, 사용자가 적은 값과 다른 메뉴는 그대로 둔다")
    void fillsOnlyEmptyDefaultMenus() throws IOException {
        long userId = createUser();
        long empty = insertMenu(userId, "김치찌개", null);
        long userSet = insertMenu(userId, "짜장면", 999);
        long other = insertMenu(userId, "우리집카레", null);

        runScript();
        runScript(); // 다시 돌려도 이미 채운 행은 대상에서 빠진다

        Object[] filled = row(empty);
        assertThat(((Number) filled[0]).intValue()).isEqualTo(230);
        assertThat(filled[1].toString()).isEqualTo("10.0");
        assertThat(filled[2].toString()).isEqualTo("16.0");
        assertThat(filled[3].toString()).isEqualTo("14.0");
        assertThat(filled[4].toString()).isEqualTo("3.0");
        assertThat(filled[5].toString()).isEqualTo("4.0");
        assertThat(((Number) filled[6]).intValue()).isEqualTo(1900);
        assertThat(((Number) filled[7]).longValue()).as("version").isEqualTo(1L);

        Object[] kept = row(userSet);
        assertThat(((Number) kept[0]).intValue()).isEqualTo(999);
        assertThat(kept[1]).isNull();
        assertThat(((Number) kept[7]).longValue()).isZero();

        assertThat(row(other)[0]).isNull();
    }

    private Object[] row(long menuId) {
        return (Object[]) em.createNativeQuery("""
                        SELECT kcal_per_serving, carbs_g, protein_g, fat_g, fiber_g, sugars_g, sodium_mg, version
                        FROM menus WHERE id = :id
                        """)
                .setParameter("id", menuId)
                .getSingleResult();
    }

    private void runScript() throws IOException {
        String sql = Files.readString(SCRIPT, StandardCharsets.UTF_8).lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);
        Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .forEach(statement -> em.createNativeQuery(statement).executeUpdate());
    }

    private long createUser() {
        em.createNativeQuery("""
                        INSERT INTO users (email, email_verified, nickname)
                        VALUES ('v27-nutrition@example.com', 1, 'v27영양소')
                        """)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE nickname = 'v27영양소'")
                .getSingleResult()).longValue();
    }

    private long insertMenu(long userId, String name, Integer kcal) {
        em.createNativeQuery("""
                        INSERT INTO menus (user_id, name, weight, is_excluded, kcal_per_serving)
                        VALUES (:userId, :name, 1, 0, NULLIF(:kcal, -1))
                        """)
                .setParameter("userId", userId)
                .setParameter("name", name)
                // 자바 null을 그대로 바인딩하면 드라이버가 타입을 못 정할 수 있다(V10 테스트와 같은 이유)
                .setParameter("kcal", kcal == null ? -1 : kcal)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM menus WHERE user_id = :userId AND name = :name")
                .setParameter("userId", userId)
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
