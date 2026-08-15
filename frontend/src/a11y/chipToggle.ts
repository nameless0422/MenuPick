/**
 * 켜고 끄는 칩 버튼의 상태를 클래스와 {@code aria-pressed}에 한 번에 준다.
 *
 * <p>칩은 눌린 상태를 색으로만 보여준다(.chip.selectable.on). 색은 보조기술로 전달되지
 * 않으므로 aria-pressed가 없으면 스크린리더에는 그냥 "한식, 버튼"으로 읽히고, 지금 이
 * 필터가 켜져 있는지 알 방법이 없다. 카테고리·거리·메뉴 선택이 전부 이 형태라
 * 안 붙이면 필터 화면 전체가 상태 없는 버튼 무더기가 된다.
 *
 * <p>두 속성을 호출부에서 따로 쓰지 않고 여기서 함께 만드는 이유는 어긋남을 막기 위해서다.
 * 클래스 조건만 손대고 aria를 빼먹으면 화면은 멀쩡한데 읽히는 상태만 틀리는, 눈으로는
 * 절대 안 보이는 버그가 된다.
 *
 * @param on 지금 선택된 상태인지
 * @param extraClass chip과 selectable 사이에 끼울 변형 클래스 (예: "chip-tag")
 */
export function chipToggle(on: boolean, extraClass?: string) {
  return {
    className: ["chip", extraClass, "selectable", on ? "on" : ""]
      .filter(Boolean)
      .join(" "),
    "aria-pressed": on,
  };
}
