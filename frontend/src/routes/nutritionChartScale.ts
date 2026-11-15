/** "2026-10-09" → { md: "10/9", weekday: "금" }. Date로 읽으면 브라우저 시간대에 따라 하루가 밀린다. */
export function splitDate(date: string): { md: string; weekday: string } {
  const [y, m, d] = date.split("-").map(Number);
  const weekday = ["일", "월", "화", "수", "목", "금", "토"][new Date(Date.UTC(y, m - 1, d)).getUTCDay()];
  return { md: `${m}/${d}`, weekday };
}

export function dayLabel(date: string): string {
  const { md, weekday } = splitDate(date);
  return `${md} (${weekday})`;
}

/** 0부터 max를 덮는 깔끔한 눈금(1·2·5 × 10ⁿ). 눈금은 3~5개. */
export function niceTicks(max: number): number[] {
  if (max <= 0) return [0, 500, 1000];
  const raw = max / 4;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 5, 10].map((f) => f * pow).find((s) => s >= raw) ?? 10 * pow;
  const ticks = [];
  for (let v = 0; v < max + step; v += step) ticks.push(v);
  return ticks;
}
