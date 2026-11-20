import { useState } from "react";
import { percentShares } from "./nutritionChartScale";

export type DonutPart = { key: string; label: string; cls: string; value: number; detail: string };

const SIZE = 160;
const OUTER = SIZE / 2 - 2;
const INNER = OUTER * 0.6;

function point(r: number, angle: number) {
  // 12시 방향에서 시작해 시계 방향으로 돈다.
  return { x: SIZE / 2 + r * Math.sin(angle), y: SIZE / 2 - r * Math.cos(angle) };
}

/** 도넛 한 조각. 한 바퀴 전체(조각이 하나뿐)면 호 하나로는 그릴 수 없어 반 바퀴 둘로 나눈다. */
function arcPath(start: number, end: number): string {
  if (end - start >= Math.PI * 2 - 1e-6) {
    return arcPath(0, Math.PI) + arcPath(Math.PI, Math.PI * 2);
  }
  const large = end - start > Math.PI ? 1 : 0;
  const o0 = point(OUTER, start), o1 = point(OUTER, end), i1 = point(INNER, end), i0 = point(INNER, start);
  return `M${o0.x},${o0.y}A${OUTER},${OUTER} 0 ${large} 1 ${o1.x},${o1.y}`
    + `L${i1.x},${i1.y}A${INNER},${INNER} 0 ${large} 0 ${i0.x},${i0.y}Z`;
}

/**
 * 기간 전체의 몫을 보여 주는 도넛. 날짜별 추이는 막대·선이 맡고, 이건 "어디서 얼마나"만 답한다.
 *
 * 옆 목록이 이름·값·몫(%)을 글자로 다 말하므로 색만으로 읽을 필요가 없다. SVG는 스크린리더에서 숨긴다.
 */
export default function NutritionDonut({ parts, center, caption }: {
  parts: DonutPart[]; center: string; caption: string;
}) {
  const [hover, setHover] = useState<string | null>(null);
  const shown = parts.filter((part) => part.value > 0);
  const shares = percentShares(shown.map((part) => part.value));
  const total = shown.reduce((sum, part) => sum + part.value, 0);
  let angle = 0;

  return (
    <div className="nutrition-chart nutrition-donut">
      <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`} aria-hidden="true"
        onMouseLeave={() => setHover(null)}>
        {total > 0 && shown.map((part) => {
          const start = angle;
          angle += (part.value / total) * Math.PI * 2;
          return (
            <path key={part.key} d={arcPath(start, angle)}
              className={`nutrition-seg nutrition-donut-slice nutrition-seg-${part.cls}`
                + (hover != null && hover !== part.key ? " is-dimmed" : "")}
              onMouseEnter={() => setHover(part.key)} />
          );
        })}
        <text className="nutrition-donut-center" x={SIZE / 2} y={SIZE / 2} textAnchor="middle" dy="0.32em">
          {center}
        </text>
      </svg>
      <div>
        <p className="nutrition-donut-caption">{caption}</p>
        <ul className="nutrition-donut-legend" role="list" aria-label={caption}>
          {shown.map((part, i) => (
            <li key={part.key} className={hover != null && hover !== part.key ? "is-dimmed" : undefined}>
              <i className={`nutrition-swatch nutrition-seg-${part.cls}`} aria-hidden="true" />
              {part.label} <strong>{shares?.[i] ?? 0}%</strong>
              <span className="eating-summary-count"> {part.detail}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
