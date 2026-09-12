import { useEffect, useId, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  deletePickPreset,
  executePickPreset,
  fetchPickPresets,
  type PickPreset,
  type PickPresetExecutionResult,
} from "../api/pickPresets";
import { apiErrorMessage as errorMessage } from "../api/http";

/**
 * 상황별 빠른 픽 — 저장해 둔 조건으로 바로 뽑는다(docs/PickPresetDesign.md 9절).
 *
 * <h2>선택해도 바로 돌지 않는다</h2>
 *
 * 프리셋을 고르면 조건을 **먼저 보여주고** 사용자가 실행 버튼을 눌러야 돈다. 자동 실행하면
 * 무엇으로 뽑았는지 모른 채 결과만 받게 되고, 특히 그 사이 기본 제외 태그가 바뀌었을 때
 * 사용자가 알아챌 자리가 없다.
 *
 * <h2>기본 제외는 여기서 보여주기만 한다</h2>
 *
 * 프리셋은 기본 제외를 **더할 수만 있고 뺄 수 없다**. 미리보기에 "실행할 때 현재 기본 제외가
 * 함께 적용된다"고 적고, 실제 적용된 조건은 서버 응답(appliedFilters)으로 다시 확인한다 —
 * 미리보기를 띄운 뒤 다른 탭에서 기본 제외를 바꿨다면 서버 값이 권위 있다.
 */
export default function PickPresets({
  tagNameOf,
  onResult,
  onBusyChange,
  resetSelectionKey = 0,
}: {
  /** 태그 id를 이름으로 바꾼다. 없으면 id를 그대로 보여준다. */
  tagNameOf: (id: number) => string;
  /** 실행이 성공하면 결과를 위로 올린다 — 결과 카드는 PickPage가 그린다. */
  onResult: (result: PickPresetExecutionResult) => void;
  /** 다른 픽 진입점도 동시에 조작되지 않도록 실행 상태를 부모에 알린다. */
  onBusyChange?: (busy: boolean) => void;
  /** 수동 조건으로 돌아갈 때 선택 중인 프리셋 미리보기를 닫는 신호다. */
  resetSelectionKey?: number;
}) {
  const queryClient = useQueryClient();
  const headingId = useId();
  const presetsQuery = useQuery({ queryKey: ["pick-presets"], queryFn: fetchPickPresets });
  const presets = presetsQuery.data?.presets ?? [];

  const [selection, setSelection] = useState<{
    resetKey: number;
    id: number | null;
  }>({ resetKey: resetSelectionKey, id: null });
  // resetSelectionKey가 바뀐 첫 렌더부터 미리보기를 닫는다. effect에서 뒤늦게 state를
  // 되돌리면 한 프레임 동안 예전 프리셋 모드가 남고 렌더도 한 번 더 필요하다.
  const selectedId = selection.resetKey === resetSelectionKey ? selection.id : null;
  // 목록이 줄어 고른 것이 사라져도(삭제·태그 정리) 여기서 null이 된다 — 렌더 중에 파생되므로
  // effect로 selectedId를 되돌릴 필요가 없다. effect를 쓰면 렌더가 한 번 더 돌 뿐이다.
  const selected = presets.find((p) => p.id === selectedId) ?? null;

  const listRef = useRef<HTMLUListElement>(null);
  const previewRef = useRef<HTMLDivElement>(null);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["pick-presets"] });

  const deleteMutation = useMutation({
    mutationFn: ({ id, version }: { id: number; version: number; name: string }) =>
      deletePickPreset(id, version),
    onSuccess: () => {
      setSelection({ resetKey: resetSelectionKey, id: null });
      listRef.current?.focus();
      invalidate();
    },
  });

  /**
   * 실행. 거리 조건이 있으면 **매번 새 위치를 받는다** — 좌표를 저장하지 않기 때문이고,
   * 저장했다면 회사에서 만든 프리셋을 집에서 눌렀을 때 회사 주변을 뽑게 된다.
   */
  const executeMutation = useMutation({
    mutationFn: async (preset: PickPreset) => {
      if (preset.maxDistance == null) {
        return executePickPreset(preset.id, { version: preset.version });
      }
      const position = await currentPosition();
      return executePickPreset(preset.id, {
        version: preset.version,
        latitude: position.latitude,
        longitude: position.longitude,
      });
    },
    onSuccess: (result) => {
      onResult(result);
      // 실행은 버전을 올리지 않지만, 태그 삭제 등으로 검토 상태가 바뀌었을 수 있어 다시 읽는다.
      invalidate();
    },
  });

  const busy = executeMutation.isPending || deleteMutation.isPending;
  useEffect(() => {
    onBusyChange?.(busy);
  }, [busy, onBusyChange]);

  return (
    <section className="card pick-presets" aria-labelledby={headingId}>
      <h2 id={headingId}>빠른 픽</h2>

      {presetsQuery.isPending && <p className="settings-desc">불러오는 중…</p>}
      {presetsQuery.isError && (
        <p className="error" role="alert">{errorMessage(presetsQuery.error)}</p>
      )}

      {presetsQuery.isSuccess && presets.length === 0 && (
        <p className="settings-desc">
          저장해 둔 빠른 픽이 없어요. 아래에서 조건을 고른 뒤 <strong>현재 조건 저장</strong>을
          누르면 다음부터 한 번에 뽑을 수 있어요.
        </p>
      )}

      {presets.length > 0 && (
        <ul ref={listRef} tabIndex={-1} className="chip-row" role="list">
          {presets.map((preset) => (
            <li key={preset.id}>
              <button
                type="button"
                className="chip chip-tag"
                aria-pressed={preset.id === selectedId}
                onClick={() =>
                  setSelection((current) => {
                    const currentId = current.resetKey === resetSelectionKey ? current.id : null;
                    return {
                      resetKey: resetSelectionKey,
                      id: currentId === preset.id ? null : preset.id,
                    };
                  })
                }
              >
                {preset.name}
                {preset.needsReview && <span aria-hidden="true"> ⚠</span>}
                {preset.needsReview && <span className="sr-only"> (확인 필요)</span>}
              </button>
            </li>
          ))}
        </ul>
      )}

      {/* 고른 뒤에도 자동으로 돌지 않는다 — 조건을 먼저 보여주고 사용자가 누른다. */}
      {selected && (
        <div className="pick-preset-preview" ref={previewRef} tabIndex={-1} role="group"
             aria-label={`${selected.name} 조건 미리보기`}>
          <strong>{selected.name}</strong>

          {selected.needsReview ? (
            <p className="error" role="alert">
              이 빠른 픽이 쓰던 태그가 삭제됐어요. 조건이 달라졌을 수 있어 지금은 실행할 수
              없습니다. 조건을 확인하고 다시 저장해주세요.
            </p>
          ) : (
            <p className="settings-desc">
              실행하면 <strong>지금의 기본 제외 태그가 함께 적용</strong>됩니다. 빠른 픽은
              기본 제외를 추가할 수만 있고 해제하지는 못해요.
            </p>
          )}

          <dl className="pick-preset-conditions">
            <dt>카테고리</dt>
            <dd>{selected.categories.length > 0 ? selected.categories.join(", ") : "전체"}</dd>
            <dt>포함 태그</dt>
            <dd>
              {selected.includeTagIds.length > 0
                ? selected.includeTagIds.map(tagNameOf).join(", ")
                : "없음"}
            </dd>
            <dt>추가 제외 태그</dt>
            <dd>
              {selected.additionalExcludeTagIds.length > 0
                ? selected.additionalExcludeTagIds.map(tagNameOf).join(", ")
                : "없음"}
            </dd>
            <dt>거리</dt>
            <dd>
              {selected.maxDistance == null
                ? "제한 없음"
                : `${selected.maxDistance}m 이내 (실행할 때 현재 위치를 받아요)`}
            </dd>
          </dl>

          {executeMutation.isError && (
            <p className="error" role="alert">{errorMessage(executeMutation.error)}</p>
          )}
          {deleteMutation.isError && (
            <p className="error" role="alert">{errorMessage(deleteMutation.error)}</p>
          )}

          <div className="card-actions">
            <button
              type="button"
              aria-busy={executeMutation.isPending}
              aria-disabled={busy || selected.needsReview || undefined}
              onClick={() => {
                // aria-disabled는 표시일 뿐 클릭을 막지 않는다. 이 조기 반환이 방어선이다.
                if (busy || selected.needsReview) return;
                executeMutation.mutate(selected);
              }}
            >
              {executeMutation.isPending ? "뽑는 중…" : "이 조건으로 뽑기"}
            </button>
            <button
              type="button"
              aria-label={`${selected.name} 삭제`}
              aria-busy={deleteMutation.isPending}
              onClick={() => {
                if (busy) return;
                if (window.confirm(`'${selected.name}' 빠른 픽을 삭제할까요?`)) {
                  deleteMutation.mutate({
                    id: selected.id,
                    version: selected.version,
                    name: selected.name,
                  });
                }
              }}
            >
              삭제
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

/**
 * 실행 직전에 위치를 한 번 받는다.
 *
 * 거부·시간초과면 **거리 조건을 끄고 그냥 뽑지 않는다** — 사용자는 "가까운 곳"을 기대했는데
 * 전혀 다른 결과를 받게 되기 때문이다. 오류를 올려 화면이 안내하게 한다.
 */
function currentPosition(): Promise<{ latitude: number; longitude: number }> {
  return new Promise((resolve, reject) => {
    if (!("geolocation" in navigator)) {
      reject(new Error("이 브라우저에서는 위치를 쓸 수 없어 거리 조건을 적용할 수 없어요."));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (position) =>
        resolve({
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
        }),
      () =>
        reject(
          new Error(
            "위치를 가져오지 못해 실행하지 않았어요. 브라우저의 위치 권한을 확인하거나, " +
              "거리 조건을 뺀 빠른 픽으로 수정해주세요.",
          ),
        ),
      { timeout: 10_000 },
    );
  });
}
