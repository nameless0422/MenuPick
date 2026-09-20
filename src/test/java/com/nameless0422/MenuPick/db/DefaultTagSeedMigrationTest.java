package com.nameless0422.MenuPick.db;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.menu.DefaultTags;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본 태그 백필(V16)을 실제 MySQL에서 검증한다.
 *
 * <p>스크립트를 다시 돌리는 이유는 {@code DefaultMenuSeedMigrationTest}와 같다 — Flyway가
 * 적용하는 시점의 테스트 DB는 비어 있어 넣는 동작이 한 번도 실행되지 않는다. 사용자가 들어 있는
 * 상태를 만들어 놓고 같은 SQL을 한 번 더 돌린다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class DefaultTagSeedMigrationTest extends AbstractIntegrationTest {

    private static final Path SCRIPT =
            Path.of("src/main/resources/db/migration/V16__seed_default_tags.sql");

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("기본 태그 백필(V16)이 성공 상태로 기록된다")
    void v16_isApplied() {
        Object applied = em.createNativeQuery(
                        "SELECT success FROM flyway_schema_history WHERE version = '16'")
                .getSingleResult();

        assertThat(applied).isNotNull();
        boolean success = (applied instanceof Boolean b) ? b : ((Number) applied).intValue() == 1;
        assertThat(success).isTrue();
    }

    @Test
    @DisplayName("인증을 마쳤고 태그가 없는 계정에만 기본 태그가 들어간다")
    void seedsOnlyVerifiedActiveAccountsWithoutTags() {
        long target = createUser("태그대상", "tag-target@example.com", true, false);
        long hasTag = createUser("태그있음", "tag-hastag@example.com", true, false);
        long pending = createUser("태그미인증", null, false, false);
        long withdrawn = createUser("태그탈퇴", "tag-withdrawn@example.com", true, true);

        insertTag(hasTag, "내가만든태그");

        runSeedScript();

        assertThat(tagNames(target)).containsExactlyInAnyOrderElementsOf(DefaultTags.NAMES);

        // 자기 어휘를 이미 만든 사용자의 필터 목록을 앱이 예고 없이 늘리지 않는다.
        assertThat(tagNames(hasTag)).containsExactly("내가만든태그");
        assertThat(tagNames(pending)).isEmpty();
        assertThat(tagNames(withdrawn)).isEmpty();
    }

    /** 태그만 만들고 메뉴에 안 붙이면, 필터 목록에 이름만 생기고 골라도 결과가 안 바뀐다. */
    @Test
    @DisplayName("이름이 같은 기본 메뉴에 태그가 연결된다")
    void linksTagsToSeededMenus() {
        long target = createUser("연결대상", "tag-link@example.com", true, false);
        insertMenu(target, "김치찌개");
        insertMenu(target, "김밥");

        runSeedScript();

        assertThat(tagNamesOfMenu(target, "김치찌개"))
                .containsExactlyInAnyOrderElementsOf(DefaultTags.MENU_TAGS.get("김치찌개"));
        assertThat(tagNamesOfMenu(target, "김밥"))
                .containsExactlyInAnyOrderElementsOf(DefaultTags.MENU_TAGS.get("김밥"));
    }

    /** 앱이 남의 메뉴를 해석해 분류하는 동작은 하지 않는다 — 이름이 정확히 같을 때만 붙인다. */
    @Test
    @DisplayName("직접 만든 메뉴에는 붙이지 않는다")
    void doesNotTagCustomMenus() {
        long target = createUser("직접메뉴", "tag-custom@example.com", true, false);
        insertMenu(target, "엄마 김치찌개");

        runSeedScript();

        assertThat(tagNames(target)).hasSize(DefaultTags.NAMES.size());
        assertThat(tagNamesOfMenu(target, "엄마 김치찌개")).isEmpty();
    }

    @Test
    @DisplayName("두 번 실행해도 태그도 연결도 두 벌 생기지 않는다")
    void rerunDoesNotDuplicate() {
        long target = createUser("태그재실행", "tag-rerun@example.com", true, false);
        insertMenu(target, "김치찌개");

        runSeedScript();
        runSeedScript();

        assertThat(tagNames(target)).hasSize(DefaultTags.NAMES.size());
        assertThat(tagNamesOfMenu(target, "김치찌개"))
                .containsExactlyInAnyOrderElementsOf(DefaultTags.MENU_TAGS.get("김치찌개"));
    }

    private void runSeedScript() {
        String sql = read(SCRIPT).lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);

        Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .forEach(statement -> em.createNativeQuery(statement).executeUpdate());
    }

    private long createUser(String nickname, String email, boolean verified, boolean withdrawn) {
        em.createNativeQuery("""
                        INSERT INTO users (email, email_verified, nickname, deleted_at)
                        VALUES (NULLIF(:email, ''), :verified, :nickname, NULLIF(:deletedAt, ''))
                        """)
                .setParameter("email", email == null ? "" : email)
                .setParameter("verified", verified ? 1 : 0)
                .setParameter("nickname", nickname)
                .setParameter("deletedAt", withdrawn ? "2026-08-01 00:00:00" : "")
                .executeUpdate();

        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE nickname = :nickname")
                .setParameter("nickname", nickname)
                .getSingleResult()).longValue();
    }

    private void insertMenu(long userId, String name) {
        em.createNativeQuery(
                        "INSERT INTO menus (user_id, name, weight, is_excluded) VALUES (:userId, :name, 1, 0)")
                .setParameter("userId", userId)
                .setParameter("name", name)
                .executeUpdate();
    }

    private void insertTag(long userId, String name) {
        em.createNativeQuery(
                        "INSERT INTO tags (user_id, name, created_at) VALUES (:userId, :name, NOW(6))")
                .setParameter("userId", userId)
                .setParameter("name", name)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private List<String> tagNames(long userId) {
        return em.createNativeQuery("SELECT name FROM tags WHERE user_id = :userId")
                .setParameter("userId", userId)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<String> tagNamesOfMenu(long userId, String menuName) {
        return em.createNativeQuery("""
                        SELECT t.name FROM menu_tags mt
                        JOIN tags t ON t.id = mt.tag_id
                        JOIN menus m ON m.id = mt.menu_id
                        WHERE m.user_id = :userId AND m.name = :menuName
                        """)
                .setParameter("userId", userId)
                .setParameter("menuName", menuName)
                .getResultList();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("마이그레이션 파일을 읽지 못했다: " + path, e);
        }
    }
}
