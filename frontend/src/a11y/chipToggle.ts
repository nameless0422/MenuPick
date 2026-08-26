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
    className: chipClass(on, extraClass),
    "aria-pressed": on,
  };
}

/**
 * 칩의 클래스만 만든다. 상태를 알리는 속성은 붙이지 않는다.
 *
 * <p>겉모습은 {@link chipToggle}과 같아야 하지만 상태를 {@code aria-pressed}로 말하면
 * 안 되는 자리를 위한 것이다 — 단일 선택 그룹(거리 필터)의 라디오가 여기 해당한다.
 * 그쪽 상태는 {@code aria-checked}가 말하고, 두 속성을 함께 붙이면 스크린리더가
 * "라디오 버튼, 선택됨, 눌림"처럼 겹쳐 읽는다. 켜짐 표시(.on)는 색이라 시각에만 쓰인다.
 */
export function chipClass(on: boolean, extraClass?: string) {
  return ["chip", extraClass, "selectable", on ? "on" : ""].filter(Boolean).join(" ");
}

/**
 * 누르면 그 자리에서 사라지는 칩 버튼의 클래스. 상태 속성은 붙이지 않는다.
 *
 * <p>태그 "제안" 칩이 여기 해당한다. 겉모습이 위 {@link chipToggle}과 같아 한동안 같은
 * 헬퍼를 {@code chipToggle(false, "chip-tag")}로 불러 썼는데, 그러면 aria-pressed="false"가
 * 함께 붙는다. aria-pressed는 "이 버튼은 눌러서 켜고 다시 눌러 끌 수 있다"는 약속이고
 * 스크린리더도 그 약속대로 "누르지 않음, 토글 버튼"이라고 읽는다. 실제 동작은 정반대다 —
 * 누르면 pressed가 true로 바뀌는 것이 아니라 선택 목록으로 옮겨 가며 이 칩은 언마운트된다.
 * 켜졌는지 확인하러 돌아온 자리에는 아무것도 없고, 다시 눌러 끌 수도 없다. 상태를 약속해
 * 놓고 지키지 않는 것은 상태를 아예 말하지 않는 것보다 나쁘다.
 *
 * <p>그래서 상태 없는 일반 버튼으로 둔다. 다만 상태를 뗀 만큼 이름이 일을 대신해야 한다 —
 * 이름이 "#혼밥"뿐이면 눌렀을 때 추가되는지 검색되는지 지워지는지 알 수 없으므로,
 * 호출부에서 aria-label로 "○○ 태그 추가"를 붙인다.
 *
 * @param extraClass chip과 selectable 사이에 끼울 변형 클래스 (예: "chip-tag")
 */
export function chipAction(extraClass?: string) {
  return {
    className: ["chip", extraClass, "selectable"].filter(Boolean).join(" "),
  };
}
