import { forwardRef } from "react";
import type { PickAlternative } from "../api/pickAlternatives";

function labelOf(alternative: PickAlternative) {
  const distance = alternative.changes.maxDistance;
  switch (alternative.type) {
    case "EXPAND_DISTANCE":
      return `거리를 ${distance}m까지 넓혀 다시 뽑기`;
    case "CLEAR_CATEGORIES":
      return "카테고리 조건을 모두 풀고 다시 뽑기";
    case "CLEAR_CATEGORIES_AND_EXPAND_DISTANCE":
      return `카테고리를 모두 풀고 거리를 ${distance}m까지 넓혀 다시 뽑기`;
  }
}

const PickAlternatives = forwardRef<HTMLDivElement, {
  alternatives: PickAlternative[];
  loading: boolean;
  applying: boolean;
  onApply: (alternative: PickAlternative) => void;
}>(function PickAlternatives({ alternatives, loading, applying, onApply }, ref) {
  if (!loading && alternatives.length === 0) return null;
  return (
    <div ref={ref} className="card pick-alternatives" tabIndex={-1} aria-busy={loading}>
      <h2>조건을 이렇게 바꿔볼까요?</h2>
      {loading ? <p role="status">가능한 조건을 찾는 중…</p> : (
        <>
          <p className="card-muted-hint">후보 수는 현재 저장된 정보를 기준으로 하며 실제 이용 가능 여부를 보장하지 않아요.</p>
          <div className="pick-alternative-actions">
            {alternatives.map((alternative) => (
              <button
                key={alternative.type}
                type="button"
                disabled={applying}
                onClick={() => { if (!applying) onApply(alternative); }}
              >
                {labelOf(alternative)} · 후보 {alternative.candidateCount}개
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
});

export default PickAlternatives;
