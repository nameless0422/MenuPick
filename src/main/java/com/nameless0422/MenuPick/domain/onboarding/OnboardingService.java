package com.nameless0422.MenuPick.domain.onboarding;

import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 첫 사용자 안내 — "안 드시는 것부터 빼 주세요".
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>가입하면 기본 메뉴 22개가 그대로 들어온다(V10). 취향이 한 톨도 반영되지 않은 상태라,
 * 첫 픽에서 평소 안 먹는 음식이 나올 확률이 꽤 높다. 이 앱을 계속 쓸지 정하는 순간이 바로
 * 그 첫 픽이므로, 그 전에 <b>빼는 것</b>만 한 번 받는다. 고르게 하지 않는 이유는 22개 중에
 * 좋아하는 것을 고르라고 하면 일이 되기 때문이다 — 못 먹는 것은 대개 몇 개뿐이다.
 *
 * <h2>상태를 따로 저장하지 않는다</h2>
 *
 * <p>"온보딩 완료" 컬럼을 두지 않고 <b>이미 있는 데이터로 판정한다</b>: 픽한 적이 있거나
 * 제외해 둔 메뉴가 하나라도 있으면 더 묻지 않는다. 컬럼을 두면 마이그레이션이 늘고, 그 값이
 * 실제 사용 상태와 어긋나는 경우(예: 온보딩 뒤 전부 되돌린 사용자)를 따로 다뤄야 한다.
 *
 * <p>대가는 "지금은 건너뛰기"를 서버가 기억하지 못한다는 것이다. 그건 브라우저에 남긴다 —
 * 기기를 바꾸면 한 번 더 보이지만, 한 번이라도 픽하면 그 뒤로는 영영 사라진다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OnboardingService {

    private final MenuRepository menuRepository;
    private final HistoryRepository historyRepository;

    public OnboardingResponse status(Long userId) {
        List<Menu> menus = menuRepository.findAllByUserIdAndDeletedAtIsNull(userId);

        boolean hasExclusions = menus.stream().anyMatch(Menu::isExcluded);
        // 픽을 한 번이라도 했으면 이미 쓰기 시작한 사용자다. 한 건만 있으면 되므로 세지 않고 존재만 본다.
        boolean hasPicked = historyRepository.existsByUserId(userId);

        return new OnboardingResponse(
                !hasExclusions && !hasPicked,
                menus.stream()
                        // 이름순으로 고정한다. 온보딩은 한 번 보는 화면이라 순서가 흔들려도
                        // 눈치채기 어렵지만, 고정해 두면 화면 검증이 쉬워진다.
                        .sorted(Comparator.comparing(Menu::getName).thenComparing(Menu::getId))
                        .map(menu -> new OnboardingResponse.Item(
                                menu.getId(), menu.getName(), List.copyOf(menu.getCategories())))
                        .toList());
    }

    /**
     * @param needed 아직 아무것도 하지 않은 새 사용자인가. 화면은 이 값이 true일 때만 안내를 띄운다.
     * @param menus  지금 가진 메뉴 전부. 기본 22개가 그대로인 상태가 보통이다.
     */
    public record OnboardingResponse(boolean needed, List<Item> menus) {
        public record Item(Long id, String name, List<String> categories) {}
    }
}
