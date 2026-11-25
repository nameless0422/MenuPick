import { useId, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchKcalReference, updateKcalReference } from "../api/nutrition";
import { apiErrorMessage } from "../api/http";

const MIN = 500;
const MAX = 6000;

/**
 * 하루 열량 기준선(D-093). 기록 화면의 열량 그래프에 점선 하나를 더 긋는 값이다.
 *
 * "목표"라고 부르지 않는다. 넘었는지·모자랐는지를 말하지 않고, 사용자가 정한 선과 눈으로 견줄 자리만 준다 —
 * 그래프의 값은 메뉴 이름으로 어림한 추정치라 판정에 쓸 만큼 정확하지 않다.
 */
export default function KcalReferenceSection() {
  const headingId = useId();
  const inputId = useId();
  const hintId = useId();
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ["nutrition-reference"], queryFn: fetchKcalReference });
  const [draft, setDraft] = useState<string | null>(null);
  const saved = query.data?.dailyKcal ?? null;
  const value = draft ?? (saved != null ? String(saved) : "");
  const parsed = value.trim() === "" ? null : Number(value);
  const invalid = parsed != null && (!Number.isInteger(parsed) || parsed < MIN || parsed > MAX);
  const dirty = parsed !== saved;

  const mutation = useMutation({
    mutationFn: (next: number | null) => updateKcalReference(next),
    onSuccess: (result) => {
      queryClient.setQueryData(["nutrition-reference"], result);
      // 그래프가 다음에 그릴 때 새 선을 쓰게 한다.
      void queryClient.invalidateQueries({ queryKey: ["history", "nutrition"] });
      setDraft(null);
    },
  });
  const blocked = !query.isSuccess || mutation.isPending || invalid || !dirty;

  return (
    <section className="card settings-section" aria-labelledby={headingId}>
      <h2 id={headingId}>열량 그래프 기준선</h2>
      <p className="settings-desc">
        하루 열량을 적어 두면 기록 화면의 열량 그래프에 점선으로 그려요. 넘었는지 모자란지는 따로 알려 주지 않아요 —
        그래프의 열량은 메뉴로 어림한 값이에요.
      </p>
      {query.isError && <p className="error" role="alert">{apiErrorMessage(query.error)}</p>}
      <form className="menu-form" onSubmit={(event) => {
        event.preventDefault();
        if (blocked) return;
        mutation.mutate(parsed);
      }}>
        <label htmlFor={inputId}>하루 기준 열량 (kcal)</label>
        <input id={inputId} type="number" inputMode="numeric" min={MIN} max={MAX} step={50}
          placeholder="비워 두면 선을 긋지 않아요" value={value} disabled={!query.isSuccess}
          aria-invalid={invalid || undefined} aria-describedby={invalid ? hintId : undefined}
          onChange={(event) => { mutation.reset(); setDraft(event.target.value); }} />
        {invalid && <p className="error" id={hintId}>{MIN.toLocaleString("ko-KR")}~{MAX.toLocaleString("ko-KR")}kcal 사이 정수로 적어 주세요.</p>}
        {mutation.isError && <p className="error" role="alert">{apiErrorMessage(mutation.error)}</p>}
        {mutation.isSuccess && (
          <p className="settings-desc" role="status">
            {saved != null ? `기준선을 ${saved.toLocaleString("ko-KR")}kcal로 저장했어요.` : "기준선을 지웠어요."}
          </p>
        )}
        <div className="card-actions">
          <button type="submit" aria-busy={mutation.isPending} aria-disabled={blocked || undefined}>
            {mutation.isPending ? "저장 중…" : "저장"}
          </button>
          {saved != null && (
            <button type="button" aria-disabled={mutation.isPending || undefined} onClick={() => {
              if (mutation.isPending) return;
              setDraft("");
              mutation.mutate(null);
            }}>
              기준선 지우기
            </button>
          )}
        </div>
      </form>
    </section>
  );
}
