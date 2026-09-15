import type { HistorySummary } from "../api/history";
import { trendComputedAtMillis as kstLocalDateTimeMillis } from "./trendValidation";

/**
 * "지난번 뽑은 메뉴, 드셨어요?"를 물을 대상 고르기.
 *
 * <h2>왜 이 질문이 필요한가</h2>
 *
 * 2026-09-16 운영 기준 픽 10회에 방문 처리 0건, 수락·거절 0건이었다. "방문했어요" 버튼은
 * 히스토리 화면에만 있는데, 사람은 뽑고 나서 밥을 먹으러 가지 히스토리를 다시 열지 않는다.
 * 방문 달력·집단 통계·개인화 보정·방문율 KPI가 전부 이 기록 위에 서 있어서, 기록이 안 쌓이면
 * 그 기능들이 모두 빈 화면이다. 그래서 사용자가 **다음에 픽 화면으로 돌아왔을 때** 묻는다.
 *
 * <h2>가장 최근 픽 하나만 묻는다</h2>
 *
 * 픽은 마음에 들 때까지 다시 돌리는 동작이라 한 끼에 기록이 여러 줄 생긴다. 그중 실제로
 * 먹었을 법한 것은 마지막으로 뽑은 것 하나다. 방문 처리 안 된 기록을 전부 물으면 다시 돌리며
 * 넘긴 메뉴들까지 줄줄이 "드셨어요?"가 되어, 질문이 성가셔지고 대답도 틀려진다.
 */

/**
 * 뽑은 지 이만큼은 지나야 묻는다. 뽑자마자 새로고침하거나 다시 돌리러 들어온 사람에게
 * "드셨어요?"는 아직 답할 수 없는 질문이다.
 */
export const MIN_PROMPT_AGE_MS = 30 * 60 * 1000;

/**
 * 이보다 오래된 픽은 묻지 않는다. 사흘 전 점심을 기억해 답하라는 것은 정확한 기록을 얻는
 * 방법이 아니다. 서버 조회도 `days=2`로 같은 창을 쓴다 — 여기서 다시 거르는 것은 기기 시계와
 * 서버 시계가 어긋날 때를 위한 것이다.
 */
export const MAX_PROMPT_AGE_MS = 48 * 60 * 60 * 1000;

/** 서버 조회 기간(일). {@link MAX_PROMPT_AGE_MS}와 같은 창이다. */
export const PROMPT_LOOKBACK_DAYS = 2;

/** "안 먹었어요"라고 답한 기록 id를 이 개수까지만 기억한다. 오래된 것은 어차피 창 밖이다. */
const MAX_REMEMBERED = 20;
const STORAGE_KEY = "menupick.visitPrompt.dismissed";

/**
 * 물을 대상을 고른다. 없으면 null.
 *
 * @param histories 서버가 최신순(id 내림차순)으로 준 목록
 * @param now 현재 시각(epoch ms). 테스트가 고정할 수 있게 받는다.
 * @param dismissedIds "안 먹었어요"라고 이미 답한 기록
 */
export function selectVisitPromptCandidate(
  histories: HistorySummary[],
  now: number,
  dismissedIds: readonly number[],
): HistorySummary | null {
  const latest = histories[0];
  if (!latest) return null;

  // 가장 최근 픽 하나만 본다. 그게 이미 처리됐으면 그 이전 픽을 대신 묻지 않는다 — 이전 것은
  // 다시 돌리며 넘긴 메뉴일 가능성이 높다(파일 주석).
  if (latest.isVisited) return null;
  // 거절한 메뉴를 두고 먹었냐고 물으면 방금 한 대답을 무시한 셈이다.
  if (latest.recommendationFeedback === "REJECTED") return null;
  // 메뉴를 지워 이름이 없으면 "무엇을" 드셨냐고 물을 수가 없다.
  if (!latest.menuName) return null;
  if (dismissedIds.includes(latest.id)) return null;

  const pickedAt = kstLocalDateTimeMillis(latest.recommendedAt);
  if (pickedAt === null) return null;
  const age = now - pickedAt;
  if (age < MIN_PROMPT_AGE_MS || age > MAX_PROMPT_AGE_MS) return null;

  return latest;
}

/**
 * 질문 문구의 "언제" 부분. "오늘 오후 12:31", "어제 오후 7:05", "9월 13일 오후 12:31".
 *
 * 날짜 비교는 KST 달력 날짜로 한다. 24시간 차이로 가르면 어젯밤 11시에 뽑고 오늘 새벽 1시에
 * 들어온 사람에게 "오늘"이라고 말하게 된다.
 */
export function describePickedAt(recommendedAt: string, now: number): string {
  const pickedAt = kstLocalDateTimeMillis(recommendedAt);
  if (pickedAt === null) return "지난번";

  // Intl.DateTimeFormat을 쓰지 않는다. ko-KR의 오전/오후 표기는 런타임의 ICU 데이터에 달려 있어
  // 같은 코드가 어떤 환경에서는 "오후 12:31", 다른 환경에서는 "PM 12:31"을 낸다(Node 테스트
  // 환경에서 실제로 갈렸다). 질문 한 줄에 필요한 형식은 고정이라 직접 만든다.
  const kst = new Date(pickedAt + KST_OFFSET_MS);
  const hour = kst.getUTCHours();
  const time = `${hour < 12 ? "오전" : "오후"} ${hour % 12 === 0 ? 12 : hour % 12}:${String(kst.getUTCMinutes()).padStart(2, "0")}`;

  const dayDiff = kstDayNumber(now) - kstDayNumber(pickedAt);
  if (dayDiff === 0) return `오늘 ${time}`;
  if (dayDiff === 1) return `어제 ${time}`;
  return `${kst.getUTCMonth() + 1}월 ${kst.getUTCDate()}일 ${time}`;
}

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** KST 기준 1970-01-01부터 며칠째인가. KST는 서머타임이 없어 고정 오프셋으로 충분하다. */
function kstDayNumber(epochMs: number): number {
  return Math.floor((epochMs + KST_OFFSET_MS) / (24 * 60 * 60 * 1000));
}

/**
 * "안 먹었어요"라고 답한 기록 id 목록.
 *
 * <h2>서버가 아니라 이 브라우저에 둔다</h2>
 *
 * 이 값은 "다시 묻지 말 것"이라는 편의일 뿐 사실 기록이 아니다. 안 먹었다는 대답을 서버에
 * 남기려면 새 컬럼이 필요한데, 거절(REJECTED)을 대신 쓰면 "이 메뉴가 별로"로 읽혀 개인화
 * 점수가 깎인다 — 안 먹은 것과 싫은 것은 다르다. 다른 기기에서 같은 질문을 한 번 더 받는
 * 정도가 이 선택의 비용이다.
 *
 * 기록 id는 계정과 무관하게 전역에서 유일하므로 한 브라우저를 여러 계정이 써도 서로의
 * 대답에 가려지지 않는다. 저장소를 못 쓰는 환경(사생활 보호 모드 등)에서는 조용히 빈 목록으로
 * 동작한다 — 최악의 경우 같은 질문을 한 번 더 받을 뿐이다.
 */
export function readDismissedVisitPrompts(): number[] {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((id): id is number => Number.isSafeInteger(id)) : [];
  } catch {
    return [];
  }
}

export function rememberDismissedVisitPrompt(historyId: number): void {
  try {
    const next = [historyId, ...readDismissedVisitPrompts().filter((id) => id !== historyId)]
      .slice(0, MAX_REMEMBERED);
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    // 저장하지 못해도 이번 화면에서는 질문이 닫힌다. 다음에 한 번 더 물을 뿐이다.
  }
}
