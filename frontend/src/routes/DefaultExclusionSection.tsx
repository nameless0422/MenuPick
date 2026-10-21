import { useId, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchAllTags } from "../api/tags";
import { fetchDefaultExcludedTagIds, updateDefaultExcludedTagIds } from "../api/pickPreferences";

export default function DefaultExclusionSection() {
  const headingId = useId();
  const searchId = useId();
  const searchRef = useRef<HTMLInputElement>(null);
  const selectedOnlyRef = useRef<HTMLInputElement>(null);
  const saveRef = useRef<HTMLButtonElement>(null);
  const queryClient = useQueryClient();
  const tagsQuery = useQuery({ queryKey: ["tags", "all"], queryFn: fetchAllTags });
  const preferenceQuery = useQuery({
    queryKey: ["pick-preferences"], queryFn: fetchDefaultExcludedTagIds,
  });
  const [selected, setSelected] = useState<number[] | null>(null);
  const [keyword, setKeyword] = useState("");
  const [selectedOnly, setSelectedOnly] = useState(false);
  const current = selected ?? preferenceQuery.data ?? [];
  const saved = preferenceQuery.data ?? [];
  const dirty = current.length !== saved.length || current.some((id) => !saved.includes(id));
  const ready = tagsQuery.isSuccess && preferenceQuery.isSuccess;
  const saveMutation = useMutation({
    mutationFn: (ids: number[]) => updateDefaultExcludedTagIds(ids),
    onMutate: () => queryClient.cancelQueries({ queryKey: ["pick-preferences"] }),
    onSuccess: (ids) => {
      queryClient.setQueryData(["pick-preferences"], ids);
      setSelected(null);
    },
  });
  const search = keyword.trim().toLocaleLowerCase();
  const tags = tagsQuery.data ?? [];
  const visible = tags.filter((tag) => tag.name.toLocaleLowerCase().includes(search)
    && (!selectedOnly || current.includes(tag.id)));
  const loading = tagsQuery.isPending || preferenceQuery.isPending;
  const loadError = tagsQuery.isError || preferenceQuery.isError;

  return (
    <section className="card settings-section default-exclusions" aria-labelledby={headingId}>
      <h2 id={headingId}>기본 제외 태그</h2>
      <p className="settings-desc">
        알레르기나 기피 재료 태그를 고르면 매번 픽할 때 기본으로 제외합니다.
        픽 화면에서는 이번 한 번만 해제할 수 있어요.
      </p>
      <p className="settings-desc">검색이나 보기 조건을 바꿔도 선택한 태그는 유지됩니다.</p>

      {tags.length > 0 && <>
        <div className="exclusion-search">
          <label htmlFor={searchId}>기본 제외 태그 검색</label>
          <div className="exclusion-search-field">
            <input id={searchId} ref={searchRef} type="search" value={keyword} maxLength={50}
              placeholder="태그 이름으로 찾기" onChange={(event) => setKeyword(event.target.value)} />
            {keyword && <button type="button" onClick={() => {
              setKeyword("");
              searchRef.current?.focus();
            }}>검색 지우기</button>}
          </div>
        </div>
        <label className="settings-check exclusion-selected-only">
          <input ref={selectedOnlyRef} type="checkbox" checked={selectedOnly}
            onChange={(event) => setSelectedOnly(event.target.checked)} />
          선택한 태그만 보기
        </label>
        <p className="settings-desc exclusion-count" role="status">
          전체 {tags.length}개 중 {visible.length}개 표시 · 선택 {current.length}개
        </p>
        <div className="exclusion-options" role="group" aria-label="기본 제외할 태그">
          {visible.map((tag) => (
            <label className="settings-check exclusion-option" key={tag.id}>
              <input type="checkbox" checked={current.includes(tag.id)}
                disabled={!ready || saveMutation.isPending} onChange={() => {
                  if (!ready || saveMutation.isPending) return;
                  if (selectedOnly) selectedOnlyRef.current?.focus();
                  saveMutation.reset();
                  setSelected(current.includes(tag.id)
                    ? current.filter((id) => id !== tag.id)
                    : [...current, tag.id]);
                }} />
              <span>{tag.name}</span>
            </label>
          ))}
        </div>
        {visible.length === 0 && <p className="settings-desc">
          {search ? "검색 조건에 맞는 태그가 없어요. 검색어나 선택한 태그 보기 조건을 바꿔 주세요."
            : "선택한 태그가 없어요. 선택한 태그만 보기를 해제해 태그를 골라 주세요."}
        </p>}
      </>}
      {loading && <p className="settings-desc">기본 제외 설정을 불러오는 중…</p>}
      {tagsQuery.isSuccess && tags.length === 0 &&
        <p className="settings-desc">먼저 메뉴에서 알레르기·기피 태그를 만들어 주세요.</p>}
      {loadError && <div>
        <p className="error" role="alert">기본 제외 설정을 불러오지 못했습니다. 선택 내용은 유지됩니다.</p>
        <button type="button" onClick={() => {
          void tagsQuery.refetch();
          void preferenceQuery.refetch();
        }}>기본 제외 다시 불러오기</button>
      </div>}
      {saveMutation.isError && <p className="error" role="alert">
        기본 제외 설정을 저장하지 못했습니다. 선택 내용을 확인한 뒤 다시 저장해 주세요.
      </p>}
      <div className="exclusion-save-area">
        <p className="settings-desc" role="status">
          {saveMutation.isPending ? "선택한 태그를 저장하고 있어요."
            : dirty ? "아직 저장하지 않은 변경이 있어요."
            : saveMutation.isSuccess ? "기본 제외 설정을 저장했습니다."
            : "저장한 태그는 다음 픽부터 적용됩니다."}
        </p>
        <div className="card-actions">
          <button ref={saveRef} type="button" aria-disabled={!ready || !dirty || saveMutation.isPending || undefined}
            aria-busy={saveMutation.isPending} onClick={() => {
              if (!ready || !dirty || saveMutation.isPending) return;
              saveMutation.mutate([...current]);
            }}>{saveMutation.isPending ? "저장 중…" : "기본 제외 저장"}</button>
          {dirty && <button type="button" aria-disabled={saveMutation.isPending || undefined}
            onClick={() => {
              if (saveMutation.isPending) return;
              setSelected(null);
              saveMutation.reset();
              saveRef.current?.focus();
            }}>변경 취소</button>}
        </div>
      </div>
    </section>
  );
}
