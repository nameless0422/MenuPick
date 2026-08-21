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
 * 콜레이션 마이그레이션(V7)이 이모지를 구분하게 만드는지 검증한다.
 *
 * <p>V1이 잡았던 utf8mb4_unicode_ci는 UCA 4.0.0 기반이라 보충 평면 문자 전체에
 * 같은 가중치를 준다 — 즉 모든 이모지가 서로 같은 문자다. 이 테스트들은 V7을
 * 되돌리면 실패한다(issue #114).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class EmojiCollationMigrationTest extends AbstractIntegrationTest {

    /** 🍕 U+1F355 */
    private static final String PIZZA = "🍕";
    /** 🍔 U+1F354 */
    private static final String BURGER = "🍔";

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("콜레이션 마이그레이션(V7)이 성공 상태로 기록된다")
    void v7_isApplied() {
        // success 컬럼은 드라이버 설정에 따라 Boolean 또는 숫자로 넘어온다
        Object applied = em.createNativeQuery(
                        "SELECT success FROM flyway_schema_history WHERE version = '7'")
                .getSingleResult();

        assertThat(applied).isNotNull();
        boolean success = (applied instanceof Boolean b) ? b : ((Number) applied).intValue() == 1;
        assertThat(success).isTrue();
    }

    @Test
    @DisplayName("서로 다른 이모지가 더 이상 같은 문자로 비교되지 않는다")
    void differentEmojisAreNotEqual() {
        // 반드시 컬럼을 거쳐서 비교해야 한다. 리터럴끼리의 SELECT 'a' = 'b'는
        // collation_connection을 쓰므로 JDBC 커넥션 설정을 볼 뿐, 이 마이그레이션이
        // 바꾼 컬럼 콜레이션과는 무관하다. 컬럼과 리터럴을 비교하면 강제성(coercibility)
        // 규칙에 따라 컬럼 쪽 콜레이션이 이긴다.
        insertUser(PIZZA);
        em.flush();

        Number matches = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM users WHERE nickname = :burger")
                .setParameter("burger", BURGER)
                .getSingleResult();

        // unicode_ci에서는 🍕가 🍔로도 조회돼 1이 나온다.
        assertThat(matches.intValue()).isZero();
    }

    @Test
    @DisplayName("이모지 닉네임 두 개가 서로를 밀어내지 않는다")
    void emojiNicknamesCoexist() {
        // V5가 붙인 uq_users_nickname이 실제로 걸리는 경로. V7 이전에는 두 번째
        // INSERT가 ERROR 1062 Duplicate entry로 거부됐다 — 먼저 온 사람이 🍕를
        // 잡으면 그다음 사람은 어떤 이모지 닉네임도 쓸 수 없었다.
        insertUser(PIZZA);
        insertUser(BURGER);
        em.flush();

        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM users WHERE nickname IN (:pizza, :burger)")
                .setParameter("pizza", PIZZA)
                .setParameter("burger", BURGER)
                .getSingleResult();

        assertThat(count.intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("영문 대소문자 무시 동작은 그대로 유지된다")
    void caseInsensitivityPreserved() {
        // 0900_ai_ci로 옮긴 목적은 이모지 구분이지, 비교 규칙 전반을 엄격하게
        // 만드는 게 아니다. 닉네임 중복확인이 대소문자에 민감해지면 회귀다.
        // 여기서도 컬럼을 거쳐야 컬럼 콜레이션이 검증된다.
        insertUser("MenuPick");
        em.flush();

        Number matches = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM users WHERE nickname = :lower")
                .setParameter("lower", "menupick")
                .getSingleResult();

        assertThat(matches.intValue()).isOne();
    }

    @Test
    @DisplayName("모든 테이블과 스키마 기본값이 0900_ai_ci로 옮겨진다")
    void everyTableConverted() {
        // 하나라도 빠지면 그 테이블만 옛 규칙으로 남아, 나중에 추가되는 컬럼이
        // 조용히 unicode_ci를 물려받는다.
        @SuppressWarnings("unchecked")
        List<String> stragglers = em.createNativeQuery(
                        "SELECT TABLE_NAME FROM information_schema.TABLES "
                                + "WHERE TABLE_SCHEMA = DATABASE() "
                                + "AND TABLE_NAME <> 'flyway_schema_history' "
                                + "AND TABLE_COLLATION <> 'utf8mb4_0900_ai_ci'")
                .getResultList();

        assertThat(stragglers).isEmpty();

        String schemaCollation = (String) em.createNativeQuery(
                        "SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA "
                                + "WHERE SCHEMA_NAME = DATABASE()")
                .getSingleResult();

        assertThat(schemaCollation).isEqualTo("utf8mb4_0900_ai_ci");
    }

    @Test
    @DisplayName("문자열 컬럼이 하나도 빠짐없이 변환된다")
    void everyStringColumnConverted() {
        @SuppressWarnings("unchecked")
        List<Object[]> stragglers = em.createNativeQuery(
                        "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = DATABASE() "
                                + "AND TABLE_NAME <> 'flyway_schema_history' "
                                + "AND COLLATION_NAME IS NOT NULL "
                                + "AND COLLATION_NAME <> 'utf8mb4_0900_ai_ci'")
                .getResultList();

        assertThat(stragglers).isEmpty();
    }

    private void insertUser(String nickname) {
        em.createNativeQuery("INSERT INTO users (nickname, email_verified) VALUES (:nickname, 0)")
                .setParameter("nickname", nickname)
                .executeUpdate();
    }
}
