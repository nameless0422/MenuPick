/** 라벨 두 줄이 겹치지 않을 최소 간격(px). 라벨 글자 높이(11px)에 여유를 조금 둔다. */
const LABEL_GAP = 12;

/**
 * 막대·선 그래프에 함께 쓰는 가로 안내선 — 기간 평균(점선)과 사용자가 정한 기준선(D-093).
 *
 * 기준선은 판정이 아니다. 색을 바꾸거나 넘은 막대를 표시하지 않고, 라벨도 "기준"뿐이다 —
 * 메뉴 이름으로 어림한 값에 선을 대면 정확해 보이기 쉬워서, 눈으로 견줄 자리만 준다.
 * 두 선이 가까우면 라벨만 위아래로 벌린다(선은 제 자리에 둔다).
 */
export default function NutritionGuideLines({ average, reference, y, x1, x2 }: {
  average: number | null;
  reference: number | null;
  y: (value: number) => number;
  x1: number;
  x2: number;
}) {
  const avgY = average != null ? y(average) : null;
  const refY = reference != null ? y(reference) : null;
  let avgLabelY = avgY;
  let refLabelY = refY;
  if (avgY != null && refY != null && Math.abs(avgY - refY) < LABEL_GAP) {
    const mid = (avgY + refY) / 2;
    const upper = mid - LABEL_GAP / 2;
    const lower = mid + LABEL_GAP / 2;
    // 값이 큰 쪽(화면에서 위)이 위 라벨을 가져간다. 같으면 기준을 위에 둔다.
    if (refY <= avgY) { refLabelY = upper; avgLabelY = lower; } else { avgLabelY = upper; refLabelY = lower; }
  }
  return (
    <>
      {refY != null && refLabelY != null && (
        <g data-guide="reference">
          <line className="nutrition-chart-reference" x1={x1} x2={x2} y1={refY} y2={refY} />
          <text className="nutrition-chart-reference-label" x={x2 + 4} y={refLabelY} dy="0.32em">기준</text>
        </g>
      )}
      {avgY != null && avgLabelY != null && (
        <g data-guide="average">
          <line className="nutrition-chart-average" x1={x1} x2={x2} y1={avgY} y2={avgY} />
          <text className="nutrition-chart-average-label" x={x2 + 4} y={avgLabelY} dy="0.32em">평균</text>
        </g>
      )}
    </>
  );
}
