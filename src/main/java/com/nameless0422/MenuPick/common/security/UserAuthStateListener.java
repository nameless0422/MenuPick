package com.nameless0422.MenuPick.common.security;

import com.nameless0422.MenuPick.domain.user.User;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code User}가 고쳐지거나 지워지면 커밋 뒤에 {@link AuthVersionCache}에서 그 사용자를 지운다.
 *
 * <p>버전을 올리는 곳(로그아웃·비밀번호 변경·탈퇴·복구·삭제)을 하나하나 찾아 부르는 대신 엔티티에
 * 건다 — 새 경로가 생겨도 {@code User}를 거치는 한 빠질 수 없다. 닉네임 변경처럼 상관없는 수정도
 * 지우지만, 그 비용은 다음 요청의 조회 한 번이다.
 *
 * <p><b>커밋 전에 지우면 안 된다.</b> 그 사이 다른 요청이 아직 보이는 옛 값을 다시 넣는다.
 * 트랜잭션이 없으면(그런 경로는 없지만) 바로 지운다.
 *
 * <p>Hibernate가 Spring 빈 컨테이너로 만들므로 생성자 주입이 된다. 캐시 빈이 없는 슬라이스
 * 테스트({@code @DataJpaTest})에서는 아무것도 하지 않는다.
 */
public class UserAuthStateListener {

    private final ObjectProvider<AuthVersionCache> cache;

    public UserAuthStateListener(ObjectProvider<AuthVersionCache> cache) {
        this.cache = cache;
    }

    @PostUpdate
    @PostRemove
    void changed(User user) {
        AuthVersionCache target = cache.getIfAvailable();
        if (target == null || user.getId() == null) return;
        Long userId = user.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    target.evict(userId);
                }
            });
        } else {
            target.evict(userId);
        }
    }
}
