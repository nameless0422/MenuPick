import { useId, useState } from "react";
import { MEAL_SLOT_LABELS, MEAL_SLOTS } from "../api/history";
import type { DayNutrition } from "../api/nutrition";
import { dayLabel, niceTicks, splitDate, unknownMealsOf, type ChartMetric } from "./nutritionChartScale";
import { MACRO_SERIES, segmentsOf, totalOf, UNIT, useWidth } from "./nutritionChartParts";
import NutritionGuideLines from "./NutritionGuideLines";

const HEIGHT = 180;
// 오른쪽 여백은 평균선 라벨 자리다. 막대 위에 얹으면 마지막 날 막대와 겹친다.
const PAD = { top: 18, right: 30, bottom: 26, left: 44 };
const GAP = 2;
const RADIUS = 4;

/** 위쪽 두 모서리만 둥근 사각형. 쌓은 막대의 맨 위 조각에 쓴다 — 바닥은 기준선에 붙어 있어야 한다. */
function topRoundedRect(x: number, y: number, w: number, h: number): string {
  const r = Math.min(RADIUS, w / 2, h);
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

/**
 * 하루 막대그래프(D-083). 열량은 끼니별로, 탄단지는 영양소별로 쌓는다(D-085).
 *
 * 그래프는 눈으로 훑기 위한 것이고, 값은 아래 표가 말한다 — SVG는 스크린리더에서 숨기고 표를 남긴다.
 * 기록이 없는 날은 막대를 그리지 않는다(0으로 보이면 "적게 먹었다"가 된다). 값을 모르는 끼니가
 * 있던 날은 막대 위에 "?"를 둔다 — 그 막대는 실제보다 짧다.
 */
export default function NutritionChart({ days, average, reference = null, metric = "kcal" }: {
  days: DayNutrition[]; average: number | null; reference?: number | null; metric?: ChartMetric;
}) {
  const id = useId();
  const [wrapRef, width] = useWidth<HTMLDivElement>(320);
  const [hover, setHover] = useState<number | null>(null);
  const unit = UNIT[metric];

  const max = Math.max(...days.map((day) => totalOf(day, metric)), average ?? 0, reference ?? 0);
  const ticks = niceTicks(max);
  const top = ticks[ticks.length - 1];
  const plotW = Math.max(1, width - PAD.left - PAD.right);
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const band = plotW / Math.max(1, days.length);
  const barW = Math.max(3, Math.min(28, band * 0.62));
  const y = (value: number) => PAD.top + plotH - (value / top) * plotH;
  // 30일이면 라벨이 겹친다. 대략 일주일 간격으로 고르고 마지막 날(오늘)은 늘 보인다.
  const labelEvery = days.length <= 10 ? 1 : 7;
  const showLabel = (i: number) => (days.length - 1 - i) % labelEvery === 0;
  const hovered = hover != null ? days[hover] : null;
  const legend = metric === "kcal"
    ? MEAL_SLOTS.map((slot) => ({ key: slot, cls: slot.toLowerCase(), label: MEAL_SLOT_LABELS[slot] }))
    : MACRO_SERIES.map((s) => ({ key: s.key, cls: s.cls, label: s.label }));

  return (
    <div className="nutrition-chart" ref={wrapRef}>
      <svg width={width} height={HEIGHT} aria-hidden="true" onMouseLeave={() => setHover(null)}>
        {ticks.map((tick) => (
          <g key={tick}>
            <line className="nutrition-chart-grid" x1={PAD.left} x2={width - PAD.right} y1={y(tick)} y2={y(tick)} />
            <text className="nutrition-chart-tick" x={PAD.left - 6} y={y(tick)} dy="0.32em" textAnchor="end">
              {tick.toLocaleString("ko-KR")}
            </text>
          </g>
        ))}
        {days.map((day, i) => {
          const cx = PAD.left + band * i + band / 2;
          const x = cx - barW / 2;
          const segments = segmentsOf(day, metric);
          let base = 0;
          return (
            <g key={day.date} className={hover != null && hover !== i ? "is-dimmed" : undefined}>
              {segments.map((segment, s) => {
                const y0 = y(base);
                base += segment.value;
                const y1 = y(base);
                const isTop = s === segments.length - 1;
                // 조각 사이 2px 틈. 맨 아래 조각은 기준선에 붙인다.
                const h = Math.max(0, y0 - y1 - (s > 0 ? GAP : 0));
                const cls = `nutrition-seg nutrition-seg-${segment.cls}`;
                return isTop
                  ? <path key={segment.key} className={cls} d={topRoundedRect(x, y1, barW, h)} />
                  : <rect key={segment.key} className={cls} x={x} y={y1} width={barW} height={h} />;
              })}
              {unknownMealsOf(day, metric) > 0 && (
                <text className="nutrition-chart-unknown" x={cx} y={y(base) - 5} textAnchor="middle">?</text>
              )}
              {showLabel(i) && (
                <text className="nutrition-chart-tick" x={cx} y={HEIGHT - PAD.bottom + 15} textAnchor="middle">
                  {splitDate(day.date).md}
                </text>
              )}
              {/* 막대보다 넓은 마우스 영역. 열 전체를 덮는다. */}
              <rect className="nutrition-chart-hit" x={PAD.left + band * i} y={PAD.top} width={band} height={plotH}
                onMouseEnter={() => setHover(i)} />
            </g>
          );
        })}
        <line className="nutrition-chart-axis" x1={PAD.left} x2={width - PAD.right} y1={y(0)} y2={y(0)} />
        <NutritionGuideLines average={average} reference={reference} y={y} x1={PAD.left} x2={width - PAD.right} />
      </svg>
      {hovered && hover != null && (
        // 막대를 가리지 않게 옆에 둔다. 오른쪽 절반이면 왼쪽으로, 왼쪽 절반이면 오른쪽으로.
        <div className="nutrition-chart-tooltip" aria-hidden="true"
          style={hover >= days.length / 2
            ? { right: width - (PAD.left + band * hover + band / 2 - barW / 2 - 6), top: PAD.top }
            : { left: PAD.left + band * hover + band / 2 + barW / 2 + 6, top: PAD.top }}>
          <strong>{dayLabel(hovered.date)}</strong>
          {hovered.meals === 0 ? <span>기록 없음</span> : (
            <>
              {segmentsOf(hovered, metric).length > 0 &&
                <span>약 {totalOf(hovered, metric).toLocaleString("ko-KR")}{unit}</span>}
              {segmentsOf(hovered, metric).filter((s) => s.label).map((s) => (
                <span key={s.key}>
                  <i className={`nutrition-swatch nutrition-seg-${s.cls}`} />
                  {s.label} {s.value.toLocaleString("ko-KR")}{metric === "macros" ? "g" : ""}
                </span>
              ))}
              {unknownMealsOf(hovered, metric) > 0 && <span>모름 {unknownMealsOf(hovered, metric)}끼</span>}
            </>
          )}
        </div>
      )}
      <ul className="nutrition-legend" role="list" aria-label={metric === "kcal" ? "그래프 색: 끼니" : "그래프 색: 영양소"}
        id={`${id}-legend`}>
        {legend.map((item) => (
          <li key={item.key}><i className={`nutrition-swatch nutrition-seg-${item.cls}`} aria-hidden="true" />
            {item.label}</li>
        ))}
        {days.some((day) => unknownMealsOf(day, metric) > 0) && <li><b aria-hidden="true">?</b> 모르는 끼니 있음</li>}
        {reference != null && <li><i className="nutrition-swatch-reference" aria-hidden="true" />
          기준 {reference.toLocaleString("ko-KR")}kcal</li>}
      </ul>
    </div>
  );
}
