import { trendComputedAtMillis } from "./trendValidation";

/**
 * 서버 시각을 KST로 해석하는 공용 도구.
 *
 * <h2>왜 필요한가</h2>
 *
 * 서버는 시간대 표시가 없는 `2026-09-07T12:00:00` 형태로 준다. 이 값은 언제나 KST다
 * (`TimeConfig.SERVICE_ZONE`, mysql 컨테이너도 `TZ=Asia/Seoul`). 그런데 `new Date(...)`에
 * 그대로 넣으면 **브라우저가 있는 지역의 시간**으로 읽는다. 한국에서 보면 맞고, 다른 시간대에서
 * 보면 몇 시간씩 밀린다 — 그 몇 시간이 "12일 전"을 "11일 전"으로, "어제"를 "오늘"로 바꾼다.
 *
 * 2026-09-19에 실제로 이 차이로 CI(UTC)와 로컬(KST)의 테스트 결과가 갈렸다. 사람이 눈으로
 * 보는 화면에서는 하루 어긋난 값이 그냥 그럴듯해 보이기 때문에, 시간대가 다른 곳에서
 * 돌려 보기 전에는 드러나지 않는다.
 */

/** 서버의 KST LocalDateTime 문자열을 epoch ms로. 형식이 아니면 null. */
export const kstLocalDateTimeMillis = trendComputedAtMillis;

/**
 * KST 기준 1970-01-01부터 며칠째인가.
 *
 * 날짜가 바뀌었는지를 24시간 차이로 판정하면, 어젯밤 11시와 오늘 새벽 1시가 "같은 날"이 된다.
 * 달력 날짜로 센다. KST는 서머타임이 없어 고정 오프셋으로 충분하다.
 */
export function kstDayNumber(epochMs: number): number {
  return Math.floor((epochMs + KST_OFFSET_MS) / (24 * 60 * 60 * 1000));
}

export const KST_OFFSET_MS = 9 * 60 * 60 * 1000;
