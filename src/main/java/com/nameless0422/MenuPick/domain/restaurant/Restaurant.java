package com.nameless0422.MenuPick.domain.restaurant;

import com.nameless0422.MenuPick.common.domain.BaseTimeEntity;
import com.nameless0422.MenuPick.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "restaurants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Restaurant extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 300)
    private String address;

    @Column(length = 20)
    private String phone;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    /** 장소 상세 링크. 카카오 검색 결과의 {@code place_url}이 들어간다(컬럼명은 초기 설계의 잔재). */
    @Column(length = 500)
    private String naverUrl;

    /**
     * 카카오 로컬 검색 결과의 장소 id. 사용자당 유일하다({@code uq_restaurants_user_place}).
     *
     * <p>같은 장소를 두 번 저장하지 않기 위한 판정 기준이다. 이름을 기준으로 삼지 않는 이유는
     * 상호가 같은 다른 가게가 실제로 존재하기 때문이다 — 이름으로 합치면 서로 다른 지점을
     * 하나로 뭉개 버린다.
     *
     * <p>NULL일 수 있다: 장소 검색을 거치지 않고 만들어진 옛 행이 그렇다. MySQL의 UNIQUE는
     * NULL을 서로 다른 값으로 보므로 그런 행은 몇 개든 공존하고 중복 판정에 참여하지 않는다.
     */
    @Column(length = 100)
    private String kakaoPlaceId;

    private LocalDateTime deletedAt;

    /**
     * 낙관적 락 버전 (issue #87, V9). 근거는 {@code Menu.version} 주석과 V9 마이그레이션.
     *
     * <p>여기서는 전체 교체 PUT 말고도 겹치는 경로가 하나 더 있다 — 지운 식당을 같은 장소로
     * 다시 등록하면 {@code restore()}가 새 행을 만드는 대신 그 행을 되살리며 필드를 덮어쓴다.
     * "탭 A에서 수정 저장"과 "탭 B에서 삭제 후 재등록"이 같은 행에서 만난다.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @Builder
    public Restaurant(User user, String name, String address, String phone,
                      BigDecimal latitude, BigDecimal longitude,
                      String naverUrl, String kakaoPlaceId) {
        this.user = user;
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.latitude = latitude;
        this.longitude = longitude;
        this.naverUrl = naverUrl;
        this.kakaoPlaceId = kakaoPlaceId;
    }

    /**
     * 사용자가 고칠 수 있는 값만 바꾼다.
     *
     * <p>{@link #kakaoPlaceId}는 받지 않는다 — 이름을 고친다고 다른 장소가 되지는 않는다.
     * 수정으로 바꿀 수 있게 두면 다른 식당의 id를 넣어 유일성 제약을 건드릴 수 있다.
     */
    public void update(String name, String address, String phone,
                       BigDecimal latitude, BigDecimal longitude,
                       String naverUrl) {
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.latitude = latitude;
        this.longitude = longitude;
        this.naverUrl = naverUrl;
    }

    /**
     * 지웠던 식당을 같은 행에 되살린다.
     *
     * <p>새로 INSERT하지 않는 이유는 {@code uq_restaurants_user_place}가 soft delete된 행까지
     * 포함해 자리를 잡고 있기 때문이다. 되살리면서 최신 검색 결과로 갱신하는 것은, 지운 뒤
     * 다시 저장한다는 것이 "예전에 적어둔 정보"보다 지금 값을 원한다는 뜻이라서다.
     */
    public void restore(String name, String address, String phone,
                        BigDecimal latitude, BigDecimal longitude, String naverUrl) {
        this.deletedAt = null;
        update(name, address, phone, latitude, longitude, naverUrl);
    }

    /** 삭제 시각은 서비스가 주입한다 — 엔티티는 Clock 빈을 주입받을 수 없다. */
    public void softDelete(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
