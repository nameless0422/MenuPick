package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.domain.pick.dto.PickResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DemoPickServiceTest {

    private final DemoPickService demoPickService = new DemoPickService();

    @Test
    @DisplayName("데모 픽은 이름과 카테고리가 채워진 결과를 반환한다")
    void pickDemo_returnsPopulatedResult() {
        PickResponse.DemoPickResult result = demoPickService.pickDemo();

        assertThat(result.name()).isNotBlank();
        assertThat(result.categories()).isNotEmpty();
        assertThat(result.categories()).allSatisfy(c -> assertThat(c).isNotBlank());
    }

    @Test
    @DisplayName("반복 호출하면 서로 다른 메뉴가 나온다 (시연으로서 의미가 있어야 한다)")
    void pickDemo_variesAcrossCalls() {
        Set<String> names = new HashSet<>();
        IntStream.range(0, 200).forEach(i -> names.add(demoPickService.pickDemo().name()));

        // 샘플이 11종이라 200회에서 한 종류만 나올 확률은 사실상 0이다.
        assertThat(names).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("데모 픽은 DB나 사용자 컨텍스트 없이 동작한다")
    void pickDemo_worksWithoutAnyCollaborator() {
        // 생성자 인자가 없다는 것 자체가 계약이다 — 리포지토리를 주입받게 되는 순간
        // 미인증 경로가 DB 커넥션을 소모하고 사용자 데이터에 닿을 수 있게 된다.
        assertThat(DemoPickService.class.getDeclaredConstructors()).hasSize(1);
        assertThat(DemoPickService.class.getDeclaredConstructors()[0].getParameterCount()).isZero();
    }
}
