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
 * V5__nickname_and_place_uniqueness.sql 검증.
 *
 * <p>서비스 코드에도 중복 검사가 있지만 그것은 <b>메시지를 위한 것</b>이고, 실제로 중복을
 * 막는 것은 여기 있는 인덱스다. 검사와 저장 사이의 경쟁을 이기는 건 DB뿐이다.
 * 인덱스가 조용히 빠지면 애플리케이션은 평소처럼 동작하면서 동시 요청에만 중복을 허용하게 된다 —
 * 그 상태는 테스트 없이는 드러나지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class UniquenessMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("닉네임 UNIQUE 인덱스가 생긴다")
    void nicknameIsUnique() {
        assertThat(indexColumns("users", "uq_users_nickname")).containsExactly("nickname");
        assertThat(isUnique("users", "uq_users_nickname")).isTrue();
    }

    @Test
    @DisplayName("식당 중복 판정은 (user_id, kakao_place_id) 조합이다 — 사용자마다 따로 센다")
    void placeIsUniquePerUser() {
        // user_id가 빠지면 남이 저장한 가게를 내가 저장할 수 없게 된다.
        assertThat(indexColumns("restaurants", "uq_restaurants_user_place"))
                .containsExactly("user_id", "kakao_place_id");
        assertThat(isUnique("restaurants", "uq_restaurants_user_place")).isTrue();
    }

    @Test
    @DisplayName("naver_place_id는 kakao_place_id로 이름이 바뀐다 — 담기는 값이 카카오 id다")
    void placeColumnRenamed() {
        assertThat(columnExists("restaurants", "kakao_place_id")).isTrue();
        assertThat(columnExists("restaurants", "naver_place_id")).isFalse();
    }

    @Test
    @DisplayName("place id가 NULL인 식당은 여럿 공존한다 — 이름이 같아도 다른 가게일 수 있다")
    void nullPlaceIdsDoNotCollide() {
        Long userId = insertUser("널판정테스터");

        insertRestaurant(userId, "같은이름집", null);
        insertRestaurant(userId, "같은이름집", null);
        em.flush();

        // MySQL의 UNIQUE는 NULL을 서로 다른 값으로 본다. 이 성질에 기대고 있으므로 못 박아 둔다 —
        // 장소 검색을 거치지 않은 옛 행들이 전부 NULL이라, 여기서 걸리면 기존 데이터가 저장 불가가 된다.
        assertThat(countRestaurants(userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 사용자가 같은 place id를 두 번 저장하면 DB가 막는다")
    void samePlaceTwiceIsRejected() {
        Long userId = insertUser("중복판정테스터");
        insertRestaurant(userId, "진주회관", "8005012");
        em.flush();

        assertThatWritingFails(() -> {
            insertRestaurant(userId, "이름은 달라도 같은 장소", "8005012");
            em.flush();
        });
    }

    @Test
    @DisplayName("다른 사용자는 같은 장소를 각자 저장할 수 있다")
    void differentUsersMayKeepTheSamePlace() {
        Long first = insertUser("첫번째테스터");
        Long second = insertUser("두번째테스터");

        insertRestaurant(first, "진주회관", "8005012");
        insertRestaurant(second, "진주회관", "8005012");
        em.flush();

        assertThat(countRestaurants(first)).isEqualTo(1);
        assertThat(countRestaurants(second)).isEqualTo(1);
    }

    // ---- 헬퍼 ----

    /**
     * 쓰기가 제약에 걸려 실패하는지 본다.
     *
     * <p>네이티브 INSERT는 {@code executeUpdate()} 시점에 바로 터지므로 flush만 감싸면
     * 예외가 밖으로 새어 나가 테스트가 실패한다. 실패 시점이 구현에 따라 갈리니 블록째로 감싼다.
     * 예외 타입도 드라이버·JPA 구현에 좌우되므로 "실패했는가"만 본다.
     */
    private void assertThatWritingFails(Runnable write) {
        try {
            write.run();
            throw new AssertionError("유니크 제약이 없어 중복 저장이 통과했다");
        } catch (RuntimeException expected) {
            // 제약 위반 — 의도한 결과다.
        }
    }

    private Long insertUser(String nickname) {
        em.createNativeQuery("INSERT INTO users (nickname, email_verified) VALUES (:nickname, 0)")
                .setParameter("nickname", nickname)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    private void insertRestaurant(Long userId, String name, String placeId) {
        em.createNativeQuery("INSERT INTO restaurants "
                        + "(user_id, name, latitude, longitude, kakao_place_id) "
                        + "VALUES (:userId, :name, 37.5, 127.0, :placeId)")
                .setParameter("userId", userId)
                .setParameter("name", name)
                .setParameter("placeId", placeId)
                .executeUpdate();
    }

    private long countRestaurants(Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM restaurants WHERE user_id = :userId")
                .setParameter("userId", userId)
                .getSingleResult()).longValue();
    }

    private boolean isUnique(String table, String index) {
        Number nonUnique = (Number) em.createNativeQuery(
                        "SELECT MAX(NON_UNIQUE) FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = :table "
                                + "AND INDEX_NAME = :index")
                .setParameter("table", table)
                .setParameter("index", index)
                .getSingleResult();
        return nonUnique != null && nonUnique.intValue() == 0;
    }

    private boolean columnExists(String table, String column) {
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = :table "
                                + "AND COLUMN_NAME = :column")
                .setParameter("table", table)
                .setParameter("column", column)
                .getSingleResult();
        return count.intValue() > 0;
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
