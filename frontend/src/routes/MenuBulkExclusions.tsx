import { useEffect, useId, useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { batchUpdateExclusions, type MenuExclusionEntry, type MenuSummary } from "../api/menus";
import { apiErrorMessage } from "../api/http";
import "./MenuBulkExclusions.css";

const SELECTION_LIMIT = 100;

export default function MenuBulkExclusions({ menus, keyword, category, loading, loadError, hasNextPage,
  onLoadMore, onReload, onClose }: {
  menus: MenuSummary[]; keyword: string; category: string; loading: boolean; loadError: boolean;
  hasNextPage: boolean; onLoadMore: () => void; onReload: () => void; onClose: () => void;
}) {
  const headingId = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  const section = useRef<HTMLElement>(null);
  const result = useRef<HTMLParagraphElement>(null);
  const restoreFocus = useRef(false);
  const [selected, setSelected] = useState<number[]>([]);
  const [message, setMessage] = useState("");
  const client = useQueryClient();
  const missing = selected.some((id) => !menus.some((menu) => menu.id === id));
  const mutation = useMutation({
    mutationFn: (entries: MenuExclusionEntry[]) => batchUpdateExclusions(entries),
    onSuccess: async (_, entries) => {
      await Promise.all(["menus", "menu", "onboarding"].map((key) =>
        client.invalidateQueries({ queryKey: [key] })));
      restoreFocus.current = Boolean(section.current?.contains(document.activeElement))
        || document.activeElement === document.body;
      setSelected([]);
      setMessage(`${entries.length}개 메뉴를 추천${entries[0].excluded ? "에서 제외" : "에 포함"}했습니다.`);
    },
    onError: () => { void client.invalidateQueries({ queryKey: ["menus"] }); },
  });
  const busy = mutation.isPending;
  const blocked = busy || loading || loadError;
  useEffect(() => { heading.current?.focus(); }, []);
  useEffect(() => {
    if (message && restoreFocus.current) {
      restoreFocus.current = false;
      result.current?.focus();
    }
  }, [message]);

  const changeSelection = (ids: number[]) => {
    if (busy) return;
    setSelected(ids);
    setMessage("");
    mutation.reset();
  };
  const apply = (excluded: boolean) => {
    if (blocked || missing || selected.length === 0 || selected.length > SELECTION_LIMIT) return;
    setMessage("");
    mutation.mutate(selected.map((menuId) => ({ menuId, excluded })));
  };

  return <section className="menu-bulk-panel" aria-labelledby={headingId} ref={section}>
    <div className="menu-bulk-heading">
      <h2 id={headingId} ref={heading} tabIndex={-1}>여러 메뉴 추천 설정</h2>
      <button type="button" aria-disabled={busy || undefined} onClick={() => { if (!busy) onClose(); }}>
        선택 종료
      </button>
    </div>
    <p>최대 100개를 골라 추천 제외·포함을 한 번에 바꿉니다.</p>
    {(keyword || category) && <p className="menu-bulk-filter">
      적용한 조건: {[keyword && `이름 ${keyword}`, category && `카테고리 ${category}`].filter(Boolean).join(" · ")}
    </p>}
    <p>아직 불러오지 않은 메뉴는 선택하지 않습니다. 검색 조건을 바꾸려면 선택을 종료해 주세요.</p>
    <p ref={result} tabIndex={-1} role="status" aria-label="여러 메뉴 설정 결과">{message}</p>
    <p role="status" aria-label="메뉴 선택 상태">불러온 {menus.length}개 · 선택 {selected.length}/100개</p>
    <div className="card-actions">
      <button type="button" aria-disabled={blocked || menus.length === 0 || undefined} onClick={() => {
        if (!blocked) changeSelection(menus.slice(0, SELECTION_LIMIT).map((menu) => menu.id));
      }}>{menus.length > SELECTION_LIMIT ? "불러온 메뉴 중 앞 100개 선택" : `불러온 ${menus.length}개 선택`}</button>
      <button type="button" aria-disabled={busy || selected.length === 0 || undefined}
        onClick={() => { if (selected.length > 0) changeSelection([]); }}>선택 초기화</button>
    </div>
    {missing && <p role="alert" className="error">
      선택한 메뉴 중 목록에서 사라진 항목이 있습니다. 선택을 초기화한 뒤 다시 골라 주세요.
    </p>}
    {loadError && <div>
      <p role="alert" className="error">메뉴를 불러오지 못했습니다. 선택은 유지했습니다.</p>
      <button type="button" aria-disabled={loading || busy || undefined}
        onClick={() => { if (!loading && !busy) onReload(); }}>선택 목록 다시 조회</button>
    </div>}
    {mutation.isError && <p role="alert" className="error">
      추천 설정을 바꾸지 못했습니다. 선택은 유지했습니다. {apiErrorMessage(mutation.error)}
    </p>}
    <ul className="menu-bulk-list" role="list">
      {menus.map((menu) => <li key={menu.id}>
        <label className="menu-bulk-option">
          <input type="checkbox" checked={selected.includes(menu.id)} aria-label={`${menu.name} 선택`}
            disabled={blocked || (selected.length >= SELECTION_LIMIT && !selected.includes(menu.id))}
            onChange={() => {
              if (blocked) return;
              changeSelection(selected.includes(menu.id) ? selected.filter((id) => id !== menu.id)
                : selected.length < SELECTION_LIMIT ? [...selected, menu.id] : selected);
            }} />
          <span><strong>{menu.name}</strong><span className="menu-bulk-current">
            {menu.isExcluded ? "현재 추천 제외" : "현재 추천 포함"}
          </span></span>
        </label>
      </li>)}
    </ul>
    {hasNextPage && <button type="button" aria-disabled={blocked || undefined}
      onClick={() => { if (!blocked) onLoadMore(); }}>{loading ? "불러오는 중…" : "선택할 메뉴 더 보기"}</button>}
    <p>추천에 포함해도 잠시 쉬기 설정과 픽 조건은 그대로 적용됩니다.</p>
    <div className="card-actions menu-bulk-apply">
      <button type="button" aria-busy={busy} aria-disabled={blocked || missing || selected.length === 0 || undefined}
        onClick={() => apply(true)}>선택한 메뉴 추천 제외</button>
      <button type="button" aria-busy={busy} aria-disabled={blocked || missing || selected.length === 0 || undefined}
        onClick={() => apply(false)}>선택한 메뉴 추천 포함</button>
    </div>
    {busy && <p role="status">선택한 메뉴의 추천 설정을 저장하고 있어요.</p>}
  </section>;
}
