package com.nameless0422.MenuPick.domain.tag;

import com.nameless0422.MenuPick.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
// DDL은 Flyway가 관리하므로 동작 변화는 없다 — 스키마(V1 uq_tags_user_name)와 엔티티의
// 불변식을 코드에서도 드러내기 위한 문서화 목적의 선언이다.
@Table(name = "tags",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_tags_user_name", columnNames = {"user_id", "name"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 50)
    private String name;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Tag(User user, String name) {
        this.user = user;
        this.name = name;
    }
}
