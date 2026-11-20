import { useState } from "react";
import type { DayNutrition } from "../api/nutrition";
import { dayLabel, niceTicks, splitDate, unknownMealsOf, type ChartMetric } from "./nutritionChartScale";
import { fmt, MACRO_SERIES, UNIT, useWidth } from "./nutritionChartParts";

const HEIGHT = 180;
const MARKER_R = 4;

type Series = { key: string; label: string; cls: string; values: (number | null)[] };

/**
 * 선이 지나는 값. 기록이 없는 날이나 그 값을 모르는 날은 null이고, 선을 거기서 끊는다 —
 * 이어 그리면 그날 먹은 양을 지어내게 된다.
 */
function seriesOf(days: DayNutrition[], metric: ChartMetric): Series[] {
  if (metric === "macros") {
    return MACRO_SERIES.map((s) => ({
      key: s.key, label: s.label, cls: s.cls,
      values: days.map((day) => (day.macros && day.macros.meals > 0 ? day.macros[s.key] ?? 0 : null)),
    }));
  }
  return [{
    key: "total", label: "하루 열량", cls: "total",
    values: days.map((day) => (day.meals > 0 && day.meals > day.unknownMeals ? day.kcal : null)),
  }];
}

/** null에서 끊기는 꺾은선 경로. 이웃 없이 혼자 있는 점은 표시점만 남는다. */
function linePath(points: ({ x: number; y: number } | null)[]): string {
  let d = "";
  let open = false;
  for (const p of points) {
    if (!p) { open = false; continue; }
    d += `${open ? "L" : "M"}${p.x.toFixed(1)},${p.y.toFixed(1)}`;
    open = true;
  }
  return d;
}

/** 선 끝 이름표가 겹치지 않게 위아래로 민다(최소 간격 12px). */
function spreadLabels(ys: number[], gap = 12): number[] {
  const order = ys.map((y, i) => [y, i] as const).sort((a, b) => a[0] - b[0]);
  const out = [...ys];
  let prev = -Infinity;
  for (const [y, i] of order) {
    const next = Math.max(y, prev + gap);
    out[i] = next;
    prev = next;
  }
  return out;
}

/**
 * 하루 꺾은선 그래프. 막대와 같은 날짜·같은 눈금이고, 열량은 하루 합계 한 줄, 탄단지는 영양소별 세 줄이다.
 * 열량은 끼니로 나눠 네 줄을 긋지 않는다 — 간식 같은 작은 줄은 0 근처에서 서로 엉킨다. 끼니별은 막대가 보여 준다.
 */
export default function NutritionLineChart({ days, average, metric = "kcal" }: {
  days: DayNutrition[]; average: number | null; metric?: ChartMetric;
}) {
  const [wrapRef, width] = useWidth<HTMLDivElement>(320);
  const [hover, setHover] = useState<number | null>(null);
  const series = seriesOf(days, metric);
  const multi = series.length > 1;
  const unit = UNIT[metric];
  // 여러 줄이면 오른쪽에 선 이름을 단다. 한 줄이면 평균선 라벨 자리만 둔다.
  const pad = { top: 18, right: multi ? 60 : 30, bottom: 26, left: 44 };

  const max = Math.max(0, ...series.flatMap((s) => s.values.map((v) => v ?? 0)), average ?? 0);
  const ticks = niceTicks(max);
  const top = ticks[ticks.length - 1];
  const plotW = Math.max(1, width - pad.left - pad.right);
  const plotH = HEIGHT - pad.top - pad.bottom;
  const band = plotW / Math.max(1, days.length);
  const cx = (i: number) => pad.left + band * i + band / 2;
  const y = (value: number) => pad.top + plotH - (value / top) * plotH;
  const labelEvery = days.length <= 10 ? 1 : 7;
  const showLabel = (i: number) => (days.length - 1 - i) % labelEvery === 0;
  const hovered = hover != null ? days[hover] : null;

  // 선 이름은 각 선의 마지막 값 높이에 단다.
  const lastIndex = series.map((s) => {
    for (let i = s.values.length - 1; i >= 0; i--) if (s.values[i] != null) return i;
    return -1;
  });
  const labelYs = spreadLabels(series.map((s, k) => (lastIndex[k] >= 0 ? y(s.values[lastIndex[k]]!) : y(0))));

  return (
    <div className="nutrition-chart" ref={wrapRef}>
      <svg width={width} height={HEIGHT} aria-hidden="true" onMouseLeave={() => setHover(null)}>
        {ticks.map((tick) => (
          <g key={tick}>
            <line className="nutrition-chart-grid" x1={pad.left} x2={width - pad.right} y1={y(tick)} y2={y(tick)} />
            <text className="nutrition-chart-tick" x={pad.left - 6} y={y(tick)} dy="0.32em" textAnchor="end">
              {fmt(tick)}
            </text>
          </g>
        ))}
        <line className="nutrition-chart-axis" x1={pad.left} x2={width - pad.right} y1={y(0)} y2={y(0)} />
        {average != null && (
          <g>
            <line className="nutrition-chart-average" x1={pad.left} x2={width - pad.right}
              y1={y(average)} y2={y(average)} />
            <text className="nutrition-chart-average-label" x={width - pad.right + 4} y={y(average)} dy="0.32em">
              평균
            </text>
          </g>
        )}
        {hover != null && (
          <line className="nutrition-chart-crosshair" x1={cx(hover)} x2={cx(hover)} y1={pad.top} y2={y(0)} />
        )}
        {series.map((s, k) => {
          const points = s.values.map((v, i) => (v == null ? null : { x: cx(i), y: y(v) }));
          return (
            <g key={s.key} className={`nutrition-line nutrition-line-${s.cls}`} data-series={s.key}>
              <path className="nutrition-line-path" d={linePath(points)} />
              {points.map((p, i) => p && (
                <circle key={i} className="nutrition-line-marker" cx={p.x} cy={p.y}
                  r={hover === i ? MARKER_R + 1 : MARKER_R} />
              ))}
              {multi && lastIndex[k] >= 0 && (
                <text className="nutrition-chart-series-label" x={width - pad.right + 6} y={labelYs[k]} dy="0.32em">
                  {s.label}
                </text>
              )}
            </g>
          );
        })}
        {days.map((day, i) => (
          <g key={day.date}>
            {unknownMealsOf(day, metric) > 0 && (
              <text className="nutrition-chart-unknown" x={cx(i)} y={pad.top - 6} textAnchor="middle">?</text>
            )}
            {showLabel(i) && (
              <text className="nutrition-chart-tick" x={cx(i)} y={HEIGHT - pad.bottom + 15} textAnchor="middle">
                {splitDate(day.date).md}
              </text>
            )}
            <rect className="nutrition-chart-hit" x={pad.left + band * i} y={pad.top} width={band} height={plotH}
              onMouseEnter={() => setHover(i)} />
          </g>
        ))}
      </svg>
      {hovered && hover != null && (
        <div className="nutrition-chart-tooltip" aria-hidden="true"
          style={hover >= days.length / 2
            ? { right: width - cx(hover) + 10, top: pad.top }
            : { left: cx(hover) + 10, top: pad.top }}>
          <strong>{dayLabel(hovered.date)}</strong>
          {hovered.meals === 0 ? <span>기록 없음</span> : (
            <>
              {series.map((s) => s.values[hover] != null && (
                <span key={s.key}>
                  {multi && <i className={`nutrition-swatch nutrition-seg-${s.cls}`} />}
                  {multi ? `${s.label} ` : "약 "}{fmt(s.values[hover]!)}{unit}
                </span>
              ))}
              {unknownMealsOf(hovered, metric) > 0 && <span>모름 {unknownMealsOf(hovered, metric)}끼</span>}
            </>
          )}
        </div>
      )}
      <ul className="nutrition-legend" role="list" aria-label={multi ? "그래프 색: 영양소" : "그래프: 하루 열량"}>
        {multi && series.map((s) => (
          <li key={s.key}><i className={`nutrition-swatch nutrition-seg-${s.cls}`} aria-hidden="true" />{s.label}</li>
        ))}
        {days.some((day) => unknownMealsOf(day, metric) > 0) && <li><b aria-hidden="true">?</b> 모르는 끼니 있음</li>}
        {series.some((s) => s.values.some((v) => v == null)) && <li>선이 끊긴 날: 기록 없음·값 모름</li>}
      </ul>
    </div>
  );
}
