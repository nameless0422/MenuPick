import { kstDayNumber, kstLocalDateTimeMillis } from "./kstTime";

/**
 * "잠시 쉬는 중"을 화면 문구로 바꾼다.
 *
 * <h2>null이 아니라 지금과 비교한다</h2>
 *
 * 서버의 `pausedUntil`은 **만료 시각**이다. 기간이 지나도 서버가 그 값을 지우지 않는다
 * (지우려면 읽기 경로에 쓰기가 끼어든다). 그래서 `pausedUntil != null`을 "쉬는 중"으로 읽으면
 * 이미 깨어난 메뉴에 계속 "쉬는 중" 배지가 붙는다. 판정은 언제나 지금과의 비교다 —
 * 서버의 후보 조회가 쓰는 `paused_until <= now`와 같은 기준이어야 화면과 결과가 어긋나지 않는다.
 *
 * <h2>남은 기간은 달력 날짜로 센다</h2>
 *
 * 24시간 단위로 세면 "오늘 밤 11시까지"가 0일 남음이 되어 사라진 것처럼 보인다. 사용자가 세는
 * 단위는 날짜라, 자정을 몇 번 넘는지로 센다({@link kstDayNumber}). 시각은 KST로 읽는다 —
 * `new Date(문자열)`은 브라우저 지역 시간으로 읽어 시간대가 다른 곳에서 하루씩 밀린다.
 */
export function isPaused(pausedUntil: string | null | undefined, nowMs: number): boolean {
  const until = pausedUntil ? kstLocalDateTimeMillis(pausedUntil) : null;
  return until != null && until > nowMs;
}

/**
 * 쉬는 중인 메뉴의 배지 문구. 쉬지 않으면 null.
 *
 * 자정을 한 번도 넘지 않으면 "오늘까지"다. "0일 남음"은 쉬고 있다는 사실과 어긋나 보인다.
 */
export function pauseLabel(
  pausedUntil: string | null | undefined,
  nowMs: number,
): string | null {
  if (!isPaused(pausedUntil, nowMs)) return null;
  const until = kstLocalDateTimeMillis(pausedUntil as string);
  if (until == null) return null;
  const days = kstDayNumber(until) - kstDayNumber(nowMs);
  if (days <= 0) return "쉬는 중 · 오늘까지";
  return `쉬는 중 · ${days}일 남음`;
}

/**
 * 고를 수 있는 기간(일).
 *
 * 서버는 1~90일을 받지만 화면은 넷만 준다. 이 기능에서 사용자가 떠올리는 단위는 "며칠",
 * "한 주", "두 주", "한 달"이고, 1일 단위 입력칸은 고를 거리만 늘린다. 90일에 가까운 기간을
 * 원한다면 그건 잠시가 아니라 추천 제외다.
 */
export const PAUSE_DAY_OPTIONS = [3, 7, 14, 30] as const;

/** 버튼에 쓰는 이름. "7일"보다 "1주"가 사용자가 떠올린 단위에 가깝다. */
export function pauseOptionLabel(days: number): string {
  if (days === 7) return "1주";
  if (days === 14) return "2주";
  if (days === 30) return "1달";
  return `${days}일`;
}
