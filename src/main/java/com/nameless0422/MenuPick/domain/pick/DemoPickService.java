package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.domain.pick.dto.PickResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 미로그인 사용자에게 랜덤 픽을 시연하는 서비스 (docs/Planning.md 4.3 게스트 접근 정책).
 *
 * <p>온보딩 퍼널용이다 — 가입 전에 "이런 식으로 뽑아준다"를 한 번 보여주고 저장하려면
 * 로그인하도록 유도한다.
 *
 * <p><b>DB를 전혀 건드리지 않는다.</b> 게스트에게는 등록된 메뉴가 없으므로 뽑을 대상이 애초에
 * 없고, 남의 데이터를 보여줄 수는 없다. 그래서 고정된 샘플 목록에서 고른다. 인증 없이 열려 있는
 * 경로라 남용 시 커넥션 풀을 소모하지 않는다는 이점도 있어, 트랜잭션이 걸린 {@link PickService}에
 * 얹지 않고 별도 서비스로 분리했다.
 *
 * <p>히스토리도 남기지 않는다 — 히스토리는 사용자에 귀속되는 기록이라 주인이 없다.
 */
@Service
public class DemoPickService {

    /**
     * 시연용 고정 샘플. 실제 사용자 데이터가 아니며, 카테고리 어휘는 화면의 프리셋
     * (frontend/src/constants.ts)과 맞춰 가입 후 경험과 이어지게 했다.
     */
    private static final List<PickResponse.DemoPickResult> SAMPLES = List.of(
            new PickResponse.DemoPickResult("김치찌개", Set.of("한식")),
            new PickResponse.DemoPickResult("제육볶음", Set.of("한식")),
            new PickResponse.DemoPickResult("냉면", Set.of("한식")),
            new PickResponse.DemoPickResult("짜장면", Set.of("중식")),
            new PickResponse.DemoPickResult("마라탕", Set.of("중식")),
            new PickResponse.DemoPickResult("초밥", Set.of("일식")),
            new PickResponse.DemoPickResult("돈까스", Set.of("일식")),
            new PickResponse.DemoPickResult("파스타", Set.of("양식")),
            new PickResponse.DemoPickResult("떡볶이", Set.of("분식")),
            new PickResponse.DemoPickResult("쌀국수", Set.of("아시안")),
            new PickResponse.DemoPickResult("햄버거", Set.of("패스트푸드"))
    );

    /**
     * 샘플에서 균등 랜덤으로 하나를 고른다. 가중치를 쓰지 않는 이유는 가중치가 사용자가
     * 직접 설정하는 값이라 게스트에게는 의미가 없기 때문이다.
     */
    public PickResponse.DemoPickResult pickDemo() {
        return SAMPLES.get(ThreadLocalRandom.current().nextInt(SAMPLES.size()));
    }
}
