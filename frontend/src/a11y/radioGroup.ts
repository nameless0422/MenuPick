import { useRef, type KeyboardEvent } from "react";

/**
 * 단일 선택 그룹을 `role="radiogroup"`으로 세우고, 그에 딸린 키보드 동작까지 함께 준다.
 *
 * <p>거리 필터 같은 "항상 정확히 하나가 선택되고 해제할 수 없는" 그룹은 토글 버튼이 아니다.
 * `aria-pressed`로 두면 스크린리더는 "500m 이내, 눌림"만 읽고 방금 300m가 풀렸다는 사실은
 * 어디에서도 전달되지 않는다 — 선택이 옮겨 간 것이 아니라 하나가 더 켜진 것처럼 들린다.
 *
 * <p>그렇다고 역할만 바꾸면 더 나쁘다. `role="radio"`는 스크린리더에게 "화살표 키로 옮겨
 * 다닐 수 있다"고 약속하는 것이고, 사용자는 실제로 그렇게 조작한다. 동작 없이 역할만
 * 붙이면 약속을 어기는 셈이라, 그럴 바에는 토글 버튼으로 두는 편이 낫다. 그래서 역할과
 * 키보드 동작을 한 훅에서 함께 내보낸다 — 호출부가 한쪽만 가져다 쓸 수 없게.
 *
 * <p>roving tabindex: 라디오 그룹은 Tab 한 번에 그룹 전체를 지나가야 한다(선택지가 늘어도
 * Tab 횟수가 늘지 않는다). 그래서 선택된 하나만 `tabIndex=0`이고 나머지는 `-1`이다.
 * 그룹 안에서의 이동은 화살표 키가 맡고, ARIA 관례대로 이동이 곧 선택이다.
 */
export function useRadioGroup<T>(
  options: readonly T[],
  selected: T,
  onSelect: (value: T) => void,
) {
  const buttons = useRef<(HTMLButtonElement | null)[]>([]);

  // 선택값이 목록에 없을 수도 있다(초기값이 어긋나거나 목록이 바뀐 직후). 그때 아무도
  // tabIndex=0을 갖지 못하면 그룹이 Tab 순회에서 통째로 사라지므로 첫 항목이 대신 받는다.
  const selectedIndex = options.indexOf(selected);
  const activeIndex = selectedIndex >= 0 ? selectedIndex : 0;

  function move(to: number) {
    const next = (to + options.length) % options.length;
    onSelect(options[next]);
    buttons.current[next]?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLElement>) {
    switch (event.key) {
      // 가로로 놓였든 세로로 놓였든 사용자가 먼저 누르는 키는 다르다. 둘 다 받는다.
      case "ArrowRight":
      case "ArrowDown":
        move(activeIndex + 1);
        break;
      case "ArrowLeft":
      case "ArrowUp":
        move(activeIndex - 1);
        break;
      case "Home":
        move(0);
        break;
      case "End":
        move(options.length - 1);
        break;
      default:
        return;
    }
    // 화살표로 페이지가 함께 스크롤되면 초점이 어디로 갔는지 놓친다.
    event.preventDefault();
  }

  return {
    groupProps: { role: "radiogroup" as const, onKeyDown },
    radioProps(value: T, index: number) {
      return {
        role: "radio" as const,
        "aria-checked": value === selected,
        tabIndex: index === activeIndex ? 0 : -1,
        ref: (element: HTMLButtonElement | null) => {
          buttons.current[index] = element;
        },
        onClick: () => onSelect(value),
      };
    },
  };
}
