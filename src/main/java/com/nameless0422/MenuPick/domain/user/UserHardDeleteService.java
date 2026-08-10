package com.nameless0422.MenuPick.domain.user;

import com.nameless0422.MenuPick.domain.auth.RefreshTokenStore;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 유예기간이 지난 유저의 모든 데이터를 하드 삭제한다.
 * FK 참조 순서(자식 → 부모)대로 삭제해야 제약 위반이 없다.
 */
@Service
@RequiredArgsConstructor
public class UserHardDeleteService {

    private final HistoryRepository historyRepository;
    private final MenuRestaurantRepository menuRestaurantRepository;
    private final MenuRepository menuRepository;
    private final TagRepository tagRepository;
    private final RestaurantRepository restaurantRepository;
    private final AuthProviderRepository authProviderRepository;
    private final UserRepository userRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final EntityManager em;

    @Transactional
    public void purge(Long userId) {
        em.flush();

        historyRepository.deleteFilterConditionsByUserId(userId);
        historyRepository.deleteAllByUserId(userId);
        menuRestaurantRepository.deleteAllByMenuOwnerId(userId);
        menuRestaurantRepository.deleteAllByRestaurantOwnerId(userId);
        menuRepository.deleteMenuTagsByUserId(userId);
        menuRepository.deleteMenuCategoriesByUserId(userId);
        menuRepository.deleteAllByUserId(userId);
        tagRepository.deleteAllByUserId(userId);
        restaurantRepository.deleteAllByUserId(userId);
        authProviderRepository.deleteAllByUserId(userId);

        // 벌크 삭제는 영속성 컨텍스트를 우회하므로, 이미 로드된 엔티티가
        // 삭제된 행을 참조한 채 남아 flush 시 오류를 내지 않도록 컨텍스트를 비운다.
        em.clear();

        userRepository.deleteById(userId);

        // DB 행이 사라져도 Redis의 Refresh Token은 TTL(최대 14일)까지 남아
        // 삭제된 유저 ID로 토큰 재발급이 계속된다. 함께 정리한다.
        refreshTokenStore.delete(userId);
    }
}
