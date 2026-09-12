package com.nameless0422.MenuPick.domain.trend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설정 검증.
 *
 * <p>{@code trends.min-users}는 환경변수로 뺀 값이다 — 이미지를 다시 빌드하지 않고 바꿀 수
 * 있다는 뜻이고, 그래서 <b>서버 재시작 한 번으로 프라이버시 경계가 사라질 수 있는 값</b>이기도
 * 하다. 1로 내리면 한 명의 선택·방문 기록이 그대로 전체 인기 순위에 오른다. 기동 시점에 막는다.
 */
class PickTrendPropertiesTest {

    @Test
    @DisplayName("최소 인원 1은 거부한다 — 그건 통계가 아니라 남의 이력이다")
    void rejectsMinUsersOne() {
        assertThatThrownBy(() -> new PickTrendProperties(true, 7, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-users");
    }

    @Test
    @DisplayName("기간과 상한도 0 이하를 거부한다")
    void rejectsNonPositiveWindowAndLimit() {
        assertThatThrownBy(() -> new PickTrendProperties(true, 0, 5, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PickTrendProperties(true, 7, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기본값은 7일·5명·10건이다")
    void defaults() {
        PickTrendProperties props = new PickTrendProperties(false, 7, 5, 10);

        assertThat(props.windowDays()).isEqualTo(7);
        assertThat(props.minUsers()).isEqualTo(5);
        assertThat(props.maxLabels()).isEqualTo(10);
        assertThat(props.enabled()).isFalse();
    }

    @Test
    void rejectsValuesAboveSafetyBounds() {
        assertThatThrownBy(() -> new PickTrendProperties(true, 31, 5, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PickTrendProperties(true, 7, 100_001, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PickTrendProperties(true, 7, 5, 21)).isInstanceOf(IllegalArgumentException.class);
    }
}
