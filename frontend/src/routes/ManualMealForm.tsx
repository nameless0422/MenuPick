import { useId, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createManualMeal,
  MEAL_SLOT_LABELS,
  MEAL_SLOTS,
  mealSlotAt,
  type MealSlot,
} from "../api/history";
import { fetchMenus } from "../api/menus";
import { apiErrorMessage } from "../api/http";
import { useFocusOnMount } from "../a11y/useFocusOnMount";
import { KST_OFFSET_MS } from "./kstTime";

/** 서버 HistoryService.MANUAL_MEAL_MAX_AGE_DAYS와 같다. */
const MAX_AGE_DAYS = 365;
/** 검색 없이 보여 줄 메뉴 수. 서버 목록 크기 상한(100)과 같다. */
const MENU_PAGE_SIZE = 100;

/** 지금의 한국 날짜·시각을 브라우저 시간대와 무관하게 만든다 — 근거는 kstTime.ts. */
function kstNowParts(now: number): { date: string; time: string } {
  const iso = new Date(now + KST_OFFSET_MS).toISOString();
  return { date: iso.slice(0, 10), time: iso.slice(11, 16) };
}

function kstDateDaysAgo(now: number, days: number): string {
  return new Date(now + KST_OFFSET_MS - days * 86_400_000).toISOString().slice(0, 10);
}

/**
 * 추천 없이 먹은 끼니를 남긴다(D-081).
 *
 * 이게 없으면 기록은 "뽑은 것"뿐이라, 추천 없이 먹은 김치찌개가 최근 제외에도 요약에도 안 잡힌다.
 * 폼은 닫혀 있는 것이 기본이다 — 기록 화면의 주인공은 여전히 목록이다.
 */
export default function ManualMealForm() {
  const [open, setOpen] = useState(false);
  const [message, setMessage] = useState("");
  const openerRef = useRef<HTMLButtonElement>(null);
  const close = (focusOpener: boolean) => {
    setOpen(false);
    if (focusOpener) openerRef.current?.focus();
  };
  return (
    <div className="manual-meal">
      <button ref={openerRef} type="button" aria-expanded={open}
        onClick={() => { if (open) close(false); else { setMessage(""); setOpen(true); } }}>
        {open ? "식사 기록 닫기" : "식사 직접 기록"}
      </button>
      <p role="status">{message}</p>
      {open && <ManualMealEditor onCancel={() => close(true)}
        onSaved={(menuName) => { setMessage(`'${menuName}' 식사를 기록했어요.`); close(true); }} />}
    </div>
  );
}

function ManualMealEditor({ onCancel, onSaved }: { onCancel: () => void; onSaved: (menuName: string) => void }) {
  const queryClient = useQueryClient();
  const id = useId();
  // 렌더마다 Date.now()를 부르면 기본값과 범위가 흔들린다. 폼을 연 순간을 기준으로 둔다.
  const [openedAt] = useState(() => Date.now());
  const today = kstNowParts(openedAt);
  const earliestDate = kstDateDaysAgo(openedAt, MAX_AGE_DAYS);

  const [searchInput, setSearchInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [menuId, setMenuId] = useState<number | null>(null);
  const [date, setDate] = useState(today.date);
  const [time, setTime] = useState(today.time);
  // 사용자가 끼니를 직접 고르기 전까지는 시각을 따라간다. 한 번 고르면 시각을 바꿔도 유지한다.
  const [chosenSlot, setChosenSlot] = useState<MealSlot | null>(null);
  const slot = chosenSlot ?? mealSlotAt(time || "12:00");
  const [memo, setMemo] = useState("");
  const searchRef = useFocusOnMount<HTMLInputElement>();

  const menusQuery = useQuery({
    queryKey: ["menus", "manual-meal", keyword],
    queryFn: () => fetchMenus(undefined, MENU_PAGE_SIZE, keyword || undefined),
  });
  const menus = menusQuery.data?.menus ?? [];
  // 검색으로 목록이 바뀌어 고른 메뉴가 사라지면 선택도 비운다 — 안 보이는 메뉴로 저장되면 안 된다.
  const selected = menus.find((menu) => menu.id === menuId) ?? null;

  const save = useMutation({
    mutationFn: () => createManualMeal({
      menuId: selected!.id,
      eatenAt: `${date}T${time.length === 5 ? `${time}:00` : time}`,
      mealSlot: slot,
      memo: memo.trim() || undefined,
    }),
    onSuccess: (created) => {
      for (const key of ["history", "history-calendar"]) void queryClient.invalidateQueries({ queryKey: [key] });
      onSaved(created.menuName ?? selected?.name ?? "메뉴");
    },
  });

  const future = date === today.date && time > today.time;
  const blocked = save.isPending || !selected || !date || !time || future;

  return (
    <form className="history-memo-form manual-meal-form" aria-label="식사 직접 기록"
      onSubmit={(event) => { event.preventDefault(); if (!blocked) save.mutate(); }}>
      <div className="manual-meal-search">
        <label htmlFor={`${id}-search`}>메뉴 찾기</label>
        <input ref={searchRef} id={`${id}-search`} type="search" value={searchInput} maxLength={100}
          placeholder="메뉴 이름" onChange={(event) => setSearchInput(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== "Enter") return;
            event.preventDefault();
            // 한글 조합 중 Enter는 조합을 끝내는 키다 — 검색으로 보내지 않는다.
            if (event.nativeEvent.isComposing || event.keyCode === 229) return;
            setKeyword(searchInput.trim());
          }} />
        <button type="button" onClick={() => setKeyword(searchInput.trim())}>메뉴 검색</button>
      </div>

      <label htmlFor={`${id}-menu`}>먹은 메뉴</label>
      <select id={`${id}-menu`} required value={selected?.id ?? ""} aria-describedby={`${id}-menu-help`}
        onChange={(event) => setMenuId(event.target.value ? Number(event.target.value) : null)}>
        <option value="">{menusQuery.isPending ? "메뉴를 불러오는 중…" : "메뉴를 고르세요"}</option>
        {menus.map((menu) => <option key={menu.id} value={menu.id}>{menu.name}</option>)}
      </select>
      <p id={`${id}-menu-help`}>
        {menusQuery.isError ? apiErrorMessage(menusQuery.error)
          : menusQuery.isSuccess && menus.length === 0
            ? (keyword ? "검색한 메뉴가 없어요." : <>아직 메뉴가 없어요. <Link to="/menus">메뉴를 먼저 추가해 주세요.</Link></>)
            : menusQuery.data?.hasNext ? `처음 ${MENU_PAGE_SIZE}개만 보여요. 없으면 이름으로 검색하세요.` : "내 메뉴에서 고릅니다."}
      </p>

      <label htmlFor={`${id}-date`}>먹은 날짜 (한국 시간)</label>
      <input id={`${id}-date`} type="date" required value={date} min={earliestDate} max={today.date}
        onChange={(event) => setDate(event.target.value)} />
      <label htmlFor={`${id}-time`}>먹은 시각 (한국 시간)</label>
      <input id={`${id}-time`} type="time" required value={time}
        max={date === today.date ? today.time : undefined} aria-describedby={`${id}-time-help`}
        onChange={(event) => setTime(event.target.value)} />
      <p id={`${id}-time-help`} className={future ? "error" : undefined}>
        {future ? "아직 오지 않은 시각이에요." : `최근 ${MAX_AGE_DAYS}일 안의 식사를 남길 수 있어요.`}
      </p>

      <fieldset className="manual-meal-slots">
        <legend>끼니</legend>
        {MEAL_SLOTS.map((option) => (
          <label key={option}>
            <input type="radio" name={`${id}-slot`} value={option} checked={slot === option}
              onChange={() => setChosenSlot(option)} />
            {MEAL_SLOT_LABELS[option]}
          </label>
        ))}
      </fieldset>

      <label htmlFor={`${id}-memo`}>메모 (선택)</label>
      <textarea id={`${id}-memo`} value={memo} maxLength={500} rows={2}
        onChange={(event) => setMemo(event.target.value)} />

      {save.isError && <p className="error" role="alert">{apiErrorMessage(save.error)}</p>}
      <div className="card-actions">
        <button type="submit" aria-disabled={blocked || undefined} aria-busy={save.isPending}>
          {save.isPending ? "기록하는 중…" : "기록하기"}
        </button>
        <button type="button" aria-disabled={save.isPending || undefined}
          onClick={() => { if (!save.isPending) onCancel(); }}>취소</button>
      </div>
    </form>
  );
}
