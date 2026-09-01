package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 갓 만들어진 계정에 {@link DefaultMenus}의 기본 메뉴를 한 번 넣어 준다.
 *
 * <p>부르는 곳은 {@code LocalAuthService.completeVerification}의 <b>새 계정 분기 하나뿐</b>이다.
 * 메일 인증을 통과하는 순간이 "이 계정이 실제로 쓰이기 시작하는" 첫 시점이라서다. 가입(signup)
 * 시점이 아닌 이유는, 그때 만들어지는 행은 인증 전이라 로그인할 수 없고 같은 주소로 다시 가입하면
 * 덮어써지는 임시 상태이기 때문이다 — 거기서 넣으면 끝내 인증하지 않은 주소마다 메뉴 22행이 쌓인다.
 *
 * <p>계정 병합·복귀 경로에서는 부르지 않는다. 둘 다 이미 존재하던 계정으로 들어가는 길이고,
 * 그 계정에는 자기가 만든 메뉴가 이미 있다.
 *
 * <p>계정 생성과 <b>같은 트랜잭션</b>에서 돈다(호출부가 연 트랜잭션에 참여한다). 별도 트랜잭션이나
 * 이벤트로 빼면 "계정은 생겼는데 메뉴만 안 들어간 계정"이 생길 수 있고, 그 상태를 나중에 알아채
 * 고칠 방법이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultMenuProvisioner {

    private final MenuRepository menuRepository;

    /**
     * @return 새로 넣은 메뉴 수. 이미 메뉴를 가진 계정이면 0
     */
    @Transactional
    public int provision(User user) {
        // 두 번 넣지 않는다. 지금은 호출 지점이 하나뿐이라 사실상 늘 비어 있지만, 이 가드가 없으면
        // 나중에 부르는 곳이 하나 늘거나 V10 백필과 시점이 겹치는 것만으로 같은 메뉴가 두 벌 생긴다.
        // menus에는 (user_id, name) UNIQUE가 없어 DB가 대신 막아 주지 않는다.
        //
        // soft delete된 메뉴도 "있음"으로 센다 — 기본 메뉴를 전부 지운 사용자에게 다시 채워 넣는
        // 동작은 이 앱이 하지 않아야 한다(DefaultMenus 클래스 주석의 "기본값의 성격").
        if (menuRepository.existsByUserId(user.getId())) {
            return 0;
        }

        List<Menu> menus = DefaultMenus.PRESETS.stream()
                .map(preset -> {
                    // 가중치는 지정하지 않는다 — 빌더가 기본값 1을 넣는다. 제외 여부도 기본 false다.
                    Menu menu = Menu.builder()
                            .user(user)
                            .name(preset.name())
                            .build();
                    menu.addCategory(preset.category());
                    return menu;
                })
                .toList();

        menuRepository.saveAll(menus);
        log.info("기본 메뉴를 생성했습니다: userId={}, count={}", user.getId(), menus.size());
        return menus.size();
    }
}
