import { useId, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchPickPreset, updatePickPreset, PRESET_DISTANCES, type PickPreset } from "../api/pickPresets";
import { fetchAllTags } from "../api/tags";
import { apiErrorCode, apiErrorMessage } from "../api/http";
import { useFocusOnMount } from "../a11y/useFocusOnMount";

export default function EditPickPreset({ id, onClose }: { id: number; onClose: () => void }) {
  const detail = useQuery({ queryKey: ["pick-preset", id], queryFn: () => fetchPickPreset(id),
    gcTime: 0, retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false });
  if (detail.isPending) return <div><p role="status">최신 조건을 불러오는 중…</p>
    <button type="button" onClick={onClose}>수정 취소</button></div>;
  if (detail.isError) return <div>
    <p role="alert" className="error">{apiErrorMessage(detail.error)}</p>
    <button type="button" aria-disabled={detail.isFetching || undefined}
      onClick={() => { if (!detail.isFetching) void detail.refetch(); }}>조건 다시 조회</button>
    <button type="button" onClick={onClose}>수정 취소</button>
  </div>;
  return <PresetForm preset={detail.data} onClose={onClose} />;
}

function PresetForm({ preset, onClose }: { preset: PickPreset; onClose: () => void }) {
  // 편집 시작 시점의 조건과 버전을 고정해 목록 재조회가 초안을 바꾸지 않게 한다.
  const [original] = useState(preset);
  const [name, setName] = useState(original.name);
  const [categoryText, setCategoryText] = useState(original.categories.join("\n"));
  const [include, setInclude] = useState(original.includeTagIds);
  const [exclude, setExclude] = useState(original.additionalExcludeTagIds);
  const [distance, setDistance] = useState(original.maxDistance == null ? "" : String(original.maxDistance));
  const [acknowledged, setAcknowledged] = useState(false);
  const tags = useQuery({ queryKey: ["tags", "all"], queryFn: fetchAllTags });
  const client = useQueryClient();
  const headingRef = useFocusOnMount<HTMLHeadingElement>();
  const id = useId();
  const categories = [...new Set(categoryText.split(/\r?\n/).map(value => value.trim()).filter(Boolean))];
  const categoryError = categories.length > 20 || categories.some(value => value.length > 20);
  const tagError = include.length > 20 || exclude.length > 20;
  const update = useMutation({
    mutationFn: () => updatePickPreset(original.id, {
      name: name.trim(), categories, includeTagIds: include, additionalExcludeTagIds: exclude,
      maxDistance: distance === "" ? null : Number(distance), version: original.version,
      acknowledgeRemovedTags: original.needsReview && acknowledged,
    }),
    onSuccess: (saved) => {
      client.setQueryData(["pick-presets"], (old: { presets: PickPreset[]; limit: number } | undefined) =>
        old ? { ...old, presets: old.presets.map(row => row.id === saved.id ? saved : row) } : old);
      void client.invalidateQueries({ queryKey: ["pick-presets"] });
      onClose();
    },
    onError: () => { void client.invalidateQueries({ queryKey: ["pick-presets"] }); },
  });
  const conflict = update.isError && ["CONCURRENT_MODIFICATION", "PICK_PRESET_NOT_FOUND", "PICK_PRESET_NEEDS_REVIEW"]
    .includes(apiErrorCode(update.error) ?? "");
  const blocked = update.isPending || conflict || !name.trim() || categoryError || tagError
    || !tags.isSuccess || (original.needsReview && !acknowledged);
  const choices = [...new Set([...(tags.data ?? []).map(tag => tag.id),
    ...original.includeTagIds, ...original.additionalExcludeTagIds, ...include, ...exclude])];

  return <form className="card pick-preset-edit" aria-labelledby={`${id}-heading`}
    onSubmit={(event) => { event.preventDefault(); if (!blocked) update.mutate(); }}>
    <h3 id={`${id}-heading`} ref={headingRef} tabIndex={-1}>빠른 픽 조건 수정</h3>
    <fieldset disabled={update.isPending}>
      <legend>저장할 조건</legend>
      <label htmlFor={`${id}-name`}>수정할 빠른 픽 이름</label>
      <input id={`${id}-name`} value={name} maxLength={30} onChange={event => setName(event.target.value)} required
        onKeyDown={event => {
          if (event.key === "Enter" && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault();
        }} />
      <label htmlFor={`${id}-categories`}>수정할 카테고리</label>
      <textarea id={`${id}-categories`} value={categoryText} maxLength={1000} rows={4}
        aria-describedby={`${id}-category-help`} onChange={event => setCategoryText(event.target.value)} />
      <p id={`${id}-category-help`}>한 줄에 하나씩 입력하세요. 최대 20개, 각각 20자입니다. 비우면 전체 카테고리를 사용합니다.</p>
      {categoryError && <p role="alert" className="error">카테고리는 20자 이하로 최대 20개까지 입력하세요.</p>}
      <label htmlFor={`${id}-distance`}>수정할 거리 조건</label>
      <select id={`${id}-distance`} value={distance} onChange={event => setDistance(event.target.value)}>
        <option value="">거리 제한 없음</option>
        {PRESET_DISTANCES.map(value => <option key={value} value={value}>{value}m 이내</option>)}
      </select>
      <p>위치는 저장하지 않습니다. 실행할 때 현재 위치를 다시 받습니다.</p>
      <fieldset>
        <legend>포함·추가 제외 태그 수정</legend>
        <p>기본 제외 태그는 실행 시점의 설정이 함께 적용되며 여기서 해제할 수 없습니다. 포함과 추가 제외는 각각 최대 20개입니다.</p>
        {tags.isPending && <p role="status">태그를 불러오는 중…</p>}
        {tags.isError && <><p role="alert" className="error">{apiErrorMessage(tags.error)}</p>
          <button type="button" aria-disabled={tags.isFetching || undefined}
            onClick={() => { if (!tags.isFetching) void tags.refetch(); }}>태그 다시 조회</button></>}
        {tags.isSuccess && choices.length === 0 && <p>선택할 태그가 없습니다.</p>}
        {tags.isSuccess && choices.map(tagId => <div key={tagId}>
          <label htmlFor={`${id}-tag-${tagId}`}>
            {tags.data.find(tag => tag.id === tagId)?.name ?? `삭제되었거나 조회되지 않은 태그 #${tagId}`}
          </label>
          <select id={`${id}-tag-${tagId}`} value={include.includes(tagId) ? "include" : exclude.includes(tagId) ? "exclude" : "none"}
            onChange={event => {
              const mode = event.target.value;
              setInclude(current => mode === "include" ? [...current.filter(value => value !== tagId), tagId] : current.filter(value => value !== tagId));
              setExclude(current => mode === "exclude" ? [...current.filter(value => value !== tagId), tagId] : current.filter(value => value !== tagId));
            }}>
            <option value="none">사용 안 함</option><option value="include">포함</option><option value="exclude">추가 제외</option>
          </select>
        </div>)}
        {tagError && <p role="alert" className="error">포함·추가 제외 태그는 각각 최대 20개입니다.</p>}
      </fieldset>
      {original.needsReview && <div>
        <p role="alert">사용하던 태그가 삭제됐습니다. 남은 전체 조건을 확인한 뒤 동의해야 다시 실행할 수 있습니다.</p>
        <label><input type="checkbox" checked={acknowledged} onChange={event => setAcknowledged(event.target.checked)} />
          남은 전체 조건을 확인했고 이 조건으로 다시 사용하겠습니다.</label>
      </div>}
    </fieldset>
    {update.isError && <p role="alert" className="error">{apiErrorMessage(update.error)}</p>}
    {conflict && <p role="status">다른 곳에서 조건이 바뀌었거나 삭제됐습니다. 입력 내용은 유지했습니다. 수정 취소 후 다시 열어 최신 조건을 확인하세요.</p>}
    <div className="card-actions">
      <button type="submit" aria-disabled={blocked || undefined} aria-busy={update.isPending}>
        {update.isPending ? "수정 저장 중…" : "수정 저장"}</button>
      <button type="button" aria-disabled={update.isPending || undefined}
        onClick={() => { if (!update.isPending) onClose(); }}>수정 취소</button>
    </div>
  </form>;
}
