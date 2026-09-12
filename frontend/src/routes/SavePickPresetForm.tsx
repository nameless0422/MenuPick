import { useId, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { createPickPreset, PRESET_DISTANCES, PRESET_LIMIT } from "../api/pickPresets";
import { apiErrorMessage as errorMessage } from "../api/http";

/**
 * 지금 고른 수동 조건을 빠른 픽으로 저장한다(docs/PickPresetDesign.md 9절).
 *
 * <h2>기본 제외를 빼 둔 상태는 저장할 수 없다</h2>
 *
 * 빠른 픽의 추가 제외는 `현재 제외 선택 − 최신 기본 제외`다. 사용자가 이번 픽에서만 기본
 * 제외 태그를 일부러 빼 뒀다면 그 해제는 저장되지 않는다 — 프리셋은 기본 제외를 더할 수만
 * 있고 뺄 수 없기 때문이다. **조용히 조건을 바꿔 저장하지 않고** 그 사실을 먼저 알린다.
 *
 * <h2>거리는 네 단계만</h2>
 *
 * 수동 화면의 거리는 자유 값이지만 프리셋은 300/500/1000/2000만 저장한다. 저장 시점에
 * 가장 가까운 단계로 맞추고 무엇으로 저장되는지 보여준다.
 */
export default function SavePickPresetForm({
  categories,
  includeTagIds,
  excludeTagIds,
  defaultExcludedTagIds,
  maxDistance,
}: {
  categories: string[];
  includeTagIds: number[];
  excludeTagIds: number[];
  /** 사용자의 현재 기본 제외. 저장될 "추가 제외"를 계산하고 해제 여부를 판정한다. */
  defaultExcludedTagIds: number[];
  /** 거리 필터가 꺼져 있으면 null. */
  maxDistance: number | null;
}) {
  const queryClient = useQueryClient();
  const nameId = useId();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");

  // 추가 제외 = 지금 제외 선택 − 기본 제외
  const additionalExcludeTagIds = excludeTagIds.filter(
    (id) => !defaultExcludedTagIds.includes(id),
  );
  // 기본 제외인데 지금 빠져 있는 것 = 사용자가 이번 픽에서만 해제한 것
  const releasedDefaults = defaultExcludedTagIds.filter((id) => !excludeTagIds.includes(id));

  const storedDistance = maxDistance == null ? null : nearestPresetDistance(maxDistance);

  const saveMutation = useMutation({
    mutationFn: () =>
      createPickPreset({
        name: name.trim(),
        categories,
        includeTagIds,
        additionalExcludeTagIds,
        maxDistance: storedDistance,
      }),
    onSuccess: () => {
      setName("");
      setOpen(false);
      queryClient.invalidateQueries({ queryKey: ["pick-presets"] });
    },
  });

  if (!open) {
    return (
      <p className="card-muted-hint">
        자주 쓰는 조건이면{" "}
        <button type="button" className="auth-inline-button" onClick={() => setOpen(true)}>
          현재 조건 저장
        </button>
        해 두고 다음부터 한 번에 뽑을 수 있어요 (최대 {PRESET_LIMIT}개).
      </p>
    );
  }

  return (
    <div className="card pick-preset-save" role="group" aria-label="현재 조건을 빠른 픽으로 저장">
      <label htmlFor={nameId}>빠른 픽 이름</label>
      <input
        id={nameId}
        value={name}
        maxLength={30}
        placeholder="예: 회사 점심"
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            // 이 컴포넌트는 폼 밖에 있지만, Enter가 상위로 새어 다른 제출을 일으키지 않도록
            // 여기서 끝낸다.
            e.preventDefault();
            if (!saveMutation.isPending && name.trim()) saveMutation.mutate();
          }
        }}
      />

      <p className="settings-desc">
        저장될 조건: 카테고리 {categories.length}개 · 포함 태그 {includeTagIds.length}개 ·
        추가 제외 {additionalExcludeTagIds.length}개 ·{" "}
        {storedDistance == null ? "거리 제한 없음" : `${storedDistance}m 이내`}
        {storedDistance != null && maxDistance !== storedDistance && (
          <> (빠른 픽은 {PRESET_DISTANCES.join("/")}m만 저장해 가장 가까운 값으로 맞춥니다)</>
        )}
      </p>

      {/* 조용히 바꿔 저장하지 않는다 — 해제는 저장되지 않는다는 것을 먼저 말한다. */}
      {releasedDefaults.length > 0 && (
        <p className="error" role="alert">
          지금 기본 제외 태그 {releasedDefaults.length}개를 빼 두셨는데,{" "}
          <strong>빠른 픽은 기본 제외를 해제할 수 없습니다.</strong> 저장하면 실행할 때 그
          태그들이 다시 적용돼요. 그대로 저장하거나, 취소하고 기본 제외를 다시 켜 주세요.
        </p>
      )}

      {saveMutation.isError && (
        <p className="error" role="alert">{errorMessage(saveMutation.error)}</p>
      )}

      <div className="card-actions">
        <button
          type="button"
          aria-busy={saveMutation.isPending}
          aria-disabled={saveMutation.isPending || !name.trim() || undefined}
          onClick={() => {
            if (saveMutation.isPending || !name.trim()) return;
            saveMutation.mutate();
          }}
        >
          {saveMutation.isPending ? "저장 중…" : "저장"}
        </button>
        <button type="button" onClick={() => { setOpen(false); setName(""); }}>
          취소
        </button>
      </div>
    </div>
  );
}

/** 수동 화면의 자유 거리를 프리셋이 저장할 수 있는 단계로 맞춘다. */
function nearestPresetDistance(meters: number): number {
  return PRESET_DISTANCES.reduce((best, option) =>
    Math.abs(option - meters) < Math.abs(best - meters) ? option : best,
  );
}
