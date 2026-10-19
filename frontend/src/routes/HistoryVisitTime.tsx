import { useEffect, useId, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchHistoryVisitTime, updateHistoryVisitTime, type HistoryVisitTimeResponse } from "../api/history";
import { apiErrorCode, apiErrorMessage } from "../api/http";
import { useFocusOnMount } from "../a11y/useFocusOnMount";

export default function HistoryVisitTime({ historyId, label }: { historyId: number; label: string }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const opener = useRef<HTMLButtonElement>(null);
  const container = useRef<HTMLDivElement>(null);
  const close = () => {
    const ownedFocus = container.current?.contains(document.activeElement);
    setOpen(false);
    if (ownedFocus) opener.current?.focus();
  };
  return <div ref={container}>
    <button ref={opener} type="button" aria-expanded={open} aria-label={`${label} 방문 일시 수정`}
      aria-disabled={busy || undefined} onClick={() => {
        if (busy) return;
        if (open) close(); else { setMessage(""); setOpen(true); }
      }}>방문 일시 수정</button>
    <p role="status">{message}</p>
    {open && <VisitTimeEditor historyId={historyId} label={label} onClose={close} onBusy={setBusy}
      onSaved={() => { setMessage("방문 일시를 수정했습니다."); close(); }} />}
  </div>;
}

function VisitTimeEditor({ historyId, label, onClose, onSaved, onBusy }: {
  historyId: number; label: string; onClose: () => void; onSaved: () => void; onBusy: (value: boolean) => void;
}) {
  const query = useQuery({ queryKey: ["history-visit-time", historyId], queryFn: () => fetchHistoryVisitTime(historyId),
    gcTime: 0, retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false });
  return <>
    {query.isPending && <p role="status">최신 방문 일시를 불러오는 중…</p>}
    {query.isError && <><p role="alert" className="error">{apiErrorMessage(query.error)}</p>
      <button type="button" aria-disabled={query.isFetching || undefined}
        onClick={() => { if (!query.isFetching) void query.refetch(); }}>방문 일시 다시 조회</button></>}
    {query.isSuccess && <VisitTimeForm initial={query.data} label={label} onClose={onClose} onSaved={onSaved} onBusy={onBusy} />}
    {!query.isSuccess && <button type="button" onClick={onClose}>방문 일시 편집 취소</button>}
  </>;
}

function VisitTimeForm({ initial, label, onClose, onSaved, onBusy }: {
  initial: HistoryVisitTimeResponse; label: string; onClose: () => void; onSaved: () => void; onBusy: (value: boolean) => void;
}) {
  const [original] = useState(initial);
  const [time, setTime] = useState(original.visitedAt.slice(0, 19));
  const client = useQueryClient();
  const id = useId();
  const focus = useFocusOnMount<HTMLInputElement>();
  const update = useMutation({
    mutationFn: () => updateHistoryVisitTime(original.historyId, time.length === 16 ? `${time}:00` : time, original.version),
    onSuccess: () => {
      for (const key of ["history", "history-calendar"]) void client.invalidateQueries({ queryKey: [key] });
      onSaved();
    },
  });
  useEffect(() => { onBusy(update.isPending); return () => onBusy(false); }, [update.isPending, onBusy]);
  const conflict = update.isError && ["CONCURRENT_MODIFICATION", "HISTORY_NOT_VISITED", "HISTORY_NOT_FOUND"]
    .includes(apiErrorCode(update.error) ?? "");
  const blocked = update.isPending || conflict || !time;
  return <form className="history-memo-form" aria-label={`${label} 방문 일시 편집`}
    onSubmit={event => { event.preventDefault(); if (!blocked) update.mutate(); }}>
    <label htmlFor={id}>방문한 날짜와 시각 (한국 시간)</label>
    <input ref={focus} id={id} type="datetime-local" step="1" required value={time}
      min={original.earliestVisitAt.slice(0, 19)} max={original.latestVisitAt.slice(0, 19)} readOnly={update.isPending}
      aria-describedby={`${id}-help`} onChange={event => setTime(event.target.value)} />
    <p id={`${id}-help`}>추천 기록이 생성된 날짜부터 조회 시점의 현재 시각까지 입력할 수 있습니다. 식당·메모·추천 피드백은 유지됩니다.</p>
    {update.isError && <p role="alert" className="error">{apiErrorMessage(update.error)}</p>}
    {conflict && <p role="status">입력한 일시는 유지했습니다. 편집을 취소한 뒤 다시 열어 최신 기록을 확인하세요.</p>}
    <div className="card-actions">
      <button type="submit" aria-disabled={blocked || undefined} aria-busy={update.isPending}>
        {update.isPending ? "방문 일시 저장 중…" : "방문 일시 저장"}</button>
      <button type="button" aria-disabled={update.isPending || undefined} onClick={() => { if (!update.isPending) onClose(); }}>
        방문 일시 편집 취소</button>
    </div>
  </form>;
}
