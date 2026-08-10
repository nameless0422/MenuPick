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

    @Column(length = 500)
    private String naverUrl;

    @Column(length = 100)
    private String naverPlaceId;

    private LocalDateTime deletedAt;

    @Builder
    public Restaurant(User user, String name, String address, String phone,
                      BigDecimal latitude, BigDecimal longitude,
                      String naverUrl, String naverPlaceId) {
        this.user = user;
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.latitude = latitude;
        this.longitude = longitude;
        this.naverUrl = naverUrl;
        this.naverPlaceId = naverPlaceId;
    }

    public void update(String name, String address, String phone,
                       BigDecimal latitude, BigDecimal longitude,
                       String naverUrl, String naverPlaceId) {
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.latitude = latitude;
        this.longitude = longitude;
        this.naverUrl = naverUrl;
        this.naverPlaceId = naverPlaceId;
    }

    /** 삭제 시각은 서비스가 주입한다 — 엔티티는 Clock 빈을 주입받을 수 없다. */
    public void softDelete(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
