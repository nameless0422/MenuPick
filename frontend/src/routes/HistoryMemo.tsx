import { useEffect, useId, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchHistoryMemo, updateHistoryMemo, type HistoryMemoResponse } from "../api/history";
import { apiErrorCode, apiErrorMessage } from "../api/http";
import { useFocusOnMount } from "../a11y/useFocusOnMount";

export default function HistoryMemo({ historyId, label, memo }: { historyId: number; label: string; memo: string | null }) {
  const [open, setOpen] = useState(false);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const openerRef = useRef<HTMLButtonElement>(null);
  const groupRef = useRef<HTMLDivElement>(null);
  const close = () => {
    const hadFocus = groupRef.current?.contains(document.activeElement);
    setOpen(false);
    if (hadFocus) openerRef.current?.focus();
  };
  return <div ref={groupRef}>
    {memo && <p className="history-memo-text">{memo}</p>}
    <button ref={openerRef} type="button" aria-expanded={open} aria-disabled={busy || undefined}
      aria-label={`${label} 메모 ${memo ? "수정" : "쓰기"}`}
      onClick={() => { if (busy) return; if (!open) { setMessage(""); setOpen(true); } else close(); }}>
      {open ? "메모 닫기" : memo ? "메모 수정" : "메모 쓰기"}
    </button>
    {message && <p role="status">{message}</p>}
    {open && <MemoEditor historyId={historyId} label={label} onClose={close} onBusyChange={setBusy}
      onSaved={() => { setMessage("메모를 저장했습니다."); close(); }} />}
  </div>;
}

function MemoEditor({ historyId, label, onClose, onSaved, onBusyChange }: {
  historyId: number; label: string; onClose: () => void; onSaved: () => void; onBusyChange: (busy: boolean) => void;
}) {
  const query = useQuery({ queryKey: ["history-memo", historyId], queryFn: () => fetchHistoryMemo(historyId),
    refetchOnMount: "always", refetchOnWindowFocus: false, refetchOnReconnect: false });
  return <>
    {(query.isPending || query.fetchStatus !== "idle") && <p role="status">메모를 불러오는 중…</p>}
    {query.isError && <div><p role="alert" className="error">{apiErrorMessage(query.error)}</p>
      <button type="button" onClick={() => { void query.refetch(); }}>메모 다시 조회</button></div>}
    {query.data && query.fetchStatus === "idle" && !query.isError && <MemoForm historyId={historyId} label={label}
      initial={query.data} onClose={onClose} onSaved={onSaved} onBusyChange={onBusyChange} />}
  </>;
}

function MemoForm({ historyId, label, initial, onClose, onSaved, onBusyChange }: {
  historyId: number; label: string; initial: HistoryMemoResponse; onClose: () => void; onSaved: () => void;
  onBusyChange: (busy: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const [text, setText] = useState(initial.memo ?? "");
  const [version, setVersion] = useState(initial.version);
  const [conflict, setConflict] = useState(false);
  const [latest, setLatest] = useState<HistoryMemoResponse | null>(null);
  const [reloading, setReloading] = useState(false);
  const [reloadError, setReloadError] = useState("");
  const inputId = useId();
  const noteId = useId();
  const focusRef = useFocusOnMount<HTMLTextAreaElement>();
  const mutation = useMutation({
    mutationFn: () => updateHistoryMemo(historyId, text, version),
    onSuccess: (data) => {
      queryClient.setQueryData(["history-memo", historyId], data);
      void queryClient.invalidateQueries({ queryKey: ["history"] });
      onSaved();
    },
    onError: (error) => { if (apiErrorCode(error) === "CONCURRENT_MODIFICATION") setConflict(true); },
  });
  const busy = mutation.isPending || reloading;
  useEffect(() => { onBusyChange(busy); return () => onBusyChange(false); }, [busy, onBusyChange]);

  const loadLatest = async () => {
    if (busy) return;
    setReloading(true);
    setReloadError("");
    try {
      const data = await fetchHistoryMemo(historyId);
      queryClient.setQueryData(["history-memo", historyId], data);
      setLatest(data);
      setVersion(data.version);
      setConflict(false);
      mutation.reset();
    } catch (error) {
      setReloadError(apiErrorMessage(error));
    } finally {
      setReloading(false);
    }
  };

  return <form className="history-memo-form" aria-label={`${label} 메모 편집`} onSubmit={(event) => {
    event.preventDefault();
    if (!busy && !conflict) mutation.mutate();
  }}>
    <label htmlFor={inputId}>픽 기록 메모</label>
    <textarea ref={focusRef} id={inputId} value={text} maxLength={500} rows={3}
      aria-describedby={noteId} readOnly={busy} onChange={(event) => setText(event.target.value)} />
    <p id={noteId}>{text.length}/500자. 비워서 저장하면 메모를 지웁니다.</p>
    {mutation.isError && <p className="error" role="alert">{apiErrorMessage(mutation.error)}</p>}
    {conflict && <div>
      <p>작성한 내용은 유지했습니다. 최신 메모를 확인한 뒤 다시 저장하세요.</p>
      <button type="button" aria-disabled={busy || undefined} onClick={() => { void loadLatest(); }}>
        {reloading ? "최신 메모를 불러오는 중…" : "최신 메모 확인"}
      </button>
    </div>}
    {reloadError && <p className="error" role="alert">{reloadError}</p>}
    {latest && <div><p>현재 저장된 메모</p><p className="history-memo-text">{latest.memo ?? "메모 없음"}</p>
      <p>위 내용을 확인하고 저장을 누르면 작성한 내용으로 바뀝니다.</p></div>}
    <div className="card-actions">
      <button type="submit" aria-disabled={busy || conflict || undefined} aria-busy={mutation.isPending}>
        {mutation.isPending ? "메모 저장 중…" : "메모 저장"}
      </button>
      <button type="button" aria-disabled={busy || undefined} onClick={() => { if (!busy) onClose(); }}>메모 편집 취소</button>
    </div>
  </form>;
}
