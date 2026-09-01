package com.nameless0422.MenuPick.db;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.menu.DefaultMenus;
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
 * 기본 메뉴 백필(V10)을 실제 MySQL에서 검증한다.
 *
 * <p><b>왜 스크립트를 다시 돌리는가.</b> Flyway가 이 마이그레이션을 적용하는 시점의 테스트 DB는
 * 비어 있다 — 대상 계정이 하나도 없으니 "성공했다"는 기록만 남고 정작 넣는 동작은 한 번도 실행되지
 * 않는다. 이 마이그레이션이 하는 일은 <b>이미 사용자가 들어 있는 DB</b>에서만 드러나므로, 그 상태를
 * 만들어 놓고 같은 SQL을 한 번 더 실행해 결과를 본다. 운영에서 이 스크립트가 만나는 것이 정확히
 * 그 상황이다.
 *
 * <p>재실행이 안전한 것도 함께 확인된다 — 대상 조건이 "메뉴가 한 건도 없는 계정"이라, 이미 받은
 * 계정은 두 번째 실행에서 대상에서 빠진다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class DefaultMenuSeedMigrationTest extends AbstractIntegrationTest {

    private static final Path SCRIPT =
            Path.of("src/main/resources/db/migration/V10__seed_default_menus.sql");

    private static final List<String> PRESET_NAMES =
            DefaultMenus.PRESETS.stream().map(DefaultMenus.Preset::name).toList();

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("기본 메뉴 백필(V10)이 성공 상태로 기록된다")
    void v10_isApplied() {
        // success 컬럼은 드라이버 설정에 따라 Boolean 또는 숫자로 넘어온다(V9 테스트와 같은 이유)
        Object applied = em.createNativeQuery(
                        "SELECT success FROM flyway_schema_history WHERE version = '10'")
                .getSingleResult();

        assertThat(applied).isNotNull();
        boolean success = (applied instanceof Boolean b) ? b : ((Number) applied).intValue() == 1;
        assertThat(success).isTrue();
    }

    @Test
    @DisplayName("인증을 마쳤고 메뉴가 없는 계정에만 기본 메뉴가 들어간다")
    void seedsOnlyVerifiedActiveAccountsWithoutMenus() {
        long target = createUser("백필대상", "backfill-target@example.com", true, false);
        long hasMenu = createUser("메뉴있음", "backfill-hasmenu@example.com", true, false);
        long pending = createUser("미인증", null, false, false);
        long withdrawn = createUser("탈퇴자", "backfill-withdrawn@example.com", true, true);

        insertMenu(hasMenu, "내가만든메뉴");

        runSeedScript();

        assertThat(menuNames(target))
                .as("인증을 마쳤고 메뉴가 없던 계정")
                .containsExactlyInAnyOrderElementsOf(PRESET_NAMES);

        // 이미 자기 목록을 갖고 있는 계정에 22개를 얹으면, 사용자 입장에서는 앱이 남의 목록을
        // 마음대로 늘린 것이다.
        assertThat(menuNames(hasMenu)).containsExactly("내가만든메뉴");

        // 미인증 계정은 로그인할 수 없고 같은 주소의 재가입에 덮어써지는 임시 상태다.
        // 인증을 통과하면 그때 DefaultMenuProvisioner가 넣는다.
        assertThat(menuNames(pending)).isEmpty();

        // 탈퇴 계정은 유예 안이면 쓰던 메뉴가 살아나고, 유예가 지나면 행 자체가 지워진다.
        assertThat(menuNames(withdrawn)).isEmpty();
    }

    @Test
    @DisplayName("기본 메뉴마다 카테고리가 정확히 하나씩 붙는다")
    void eachSeededMenuGetsItsCategory() {
        long target = createUser("카테고리확인", "backfill-category@example.com", true, false);

        runSeedScript();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT m.name, c.category
                        FROM menus m
                        JOIN menu_categories c ON c.menu_id = m.id
                        WHERE m.user_id = :userId
                        """)
                .setParameter("userId", target)
                .getResultList();

        // 시드 이름이 겹치면 4번 INSERT의 조인이 한 메뉴에 카테고리를 두 개 붙인다 —
        // 행 수가 시드 개수와 같다는 것이 그 사고가 없었다는 뜻이다.
        assertThat(rows).hasSize(DefaultMenus.PRESETS.size());
        assertThat(rows)
                .extracting(row -> new DefaultMenus.Preset((String) row[0], (String) row[1]))
                .containsExactlyInAnyOrderElementsOf(DefaultMenus.PRESETS);
    }

    @Test
    @DisplayName("두 번 실행해도 메뉴가 두 벌 생기지 않는다")
    void rerunDoesNotDuplicate() {
        long target = createUser("재실행", "backfill-rerun@example.com", true, false);

        runSeedScript();
        runSeedScript();

        assertThat(menuNames(target)).hasSize(DefaultMenus.PRESETS.size());
    }

    /**
     * 마이그레이션 파일을 그대로 실행한다.
     *
     * <p>주석 줄을 걷어낸 뒤 {@code ;}로 자른다 — 이 스크립트에는 문자열 리터럴 안에 세미콜론이
     * 없고 프로시저 정의도 없어 이 단순한 분리로 충분하다. 파일을 사본으로 두지 않고 읽어서 도는
     * 이유는, 사본을 두면 정작 배포되는 SQL이 아니라 사본을 검증하게 되기 때문이다.
     */
    private void runSeedScript() {
        String sql = read(SCRIPT).lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);

        Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .forEach(statement -> em.createNativeQuery(statement).executeUpdate());
    }

    /**
     * {@code NULLIF(:x, '')}로 NULL을 넘긴다. 네이티브 쿼리에 자바 null을 그대로 바인딩하면
     * 드라이버가 타입을 추론하지 못해 실패할 수 있어서다.
     */
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

    @SuppressWarnings("unchecked")
    private List<String> menuNames(long userId) {
        return em.createNativeQuery("SELECT name FROM menus WHERE user_id = :userId")
                .setParameter("userId", userId)
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
