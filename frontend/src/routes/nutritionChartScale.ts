import type { DayNutrition } from "../api/nutrition";

/** 그래프가 무엇을 쌓는가. 열량은 끼니별로, 탄단지는 영양소별로(D-085). */
export type ChartMetric = "kcal" | "macros";

/** 그 값을 모르는 끼니 수. 그날 막대는 실제보다 짧다. */
export function unknownMealsOf(day: DayNutrition, metric: ChartMetric): number {
  return metric === "kcal" ? day.unknownMeals : Math.max(0, day.meals - (day.macros?.meals ?? 0));
}

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

/**
 * 탄단지가 열량에서 차지하는 몫(%)을 정수로. 탄수화물·단백질 4kcal/g, 지방 9kcal/g.
 * 반올림하면 합이 99나 101이 되므로, 버린 나머지가 큰 순서로 1씩 더해 합을 100에 맞춘다.
 * 셋 다 0이면 null.
 */
export function energyShares(carbsG: number, proteinG: number, fatG: number): [number, number, number] | null {
  const shares = percentShares([carbsG * 4, proteinG * 4, fatG * 9]);
  return shares ? [shares[0], shares[1], shares[2]] : null;
}

/** 값들의 몫(%)을 정수로, 합이 정확히 100이 되게(가장 큰 나머지 순). 합이 0이면 null. */
export function percentShares(values: number[]): number[] | null {
  const total = values.reduce((sum, v) => sum + v, 0);
  if (total <= 0) return null;
  const raw = values.map((v) => (v / total) * 100);
  const shares = raw.map(Math.floor);
  const order = raw.map((v, i) => [v - Math.floor(v), i] as const).sort((a, b) => b[0] - a[0]);
  const missing = 100 - shares.reduce((sum, v) => sum + v, 0);
  for (let k = 0; k < missing; k++) shares[order[k][1]] += 1;
  return shares;
}
