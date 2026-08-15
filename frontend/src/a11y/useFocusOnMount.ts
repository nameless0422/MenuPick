import { useEffect, useRef } from "react";

/**
 * 마운트되는 순간 초점을 이 요소로 옮긴다. 반환한 ref를 폼의 제목에 붙여 쓴다.
 *
 * <p>메뉴·식당의 "수정" 버튼은 카드 전체를 폼으로 갈아끼운다. 즉 방금 누른 버튼이 사라지고,
 * 브라우저는 갈 곳 잃은 초점을 {@code <body>}로 되돌린다. 키보드 사용자는 Tab을 누르면
 * 폼이 아니라 페이지 맨 위 내비게이션으로 튀고, 스크린리더 사용자는 폼이 열린 사실조차
 * 듣지 못한다. 초점을 폼 제목으로 옮기면 무엇이 열렸는지 읽히고, 이어지는 Tab이 폼 안의
 * 첫 입력으로 들어간다.
 *
 * <p>첫 입력이 아니라 제목에 거는 이유는 "메뉴 수정"인지 "새 메뉴"인지부터 들려야 하기
 * 때문이다. 입력으로 바로 뛰면 값이 왜 채워져 있는지 알 수 없다.
 *
 * <p>붙이는 요소에 {@code tabIndex={-1}}가 필요하다. 제목은 원래 초점을 받지 않는 요소라
 * 이게 없으면 focus()가 조용히 무시된다. -1이므로 Tab 순서에는 끼지 않는다.
 */
export function useFocusOnMount<T extends HTMLElement>() {
  const ref = useRef<T>(null);

  useEffect(() => {
    ref.current?.focus();
  }, []);

  return ref;
}
