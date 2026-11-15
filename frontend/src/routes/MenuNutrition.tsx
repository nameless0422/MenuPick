import { type KeyboardEvent, useId, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  fetchMenuNutrition,
  searchFoodReferences,
  updateMenuNutrition,
  type FoodReference,
  type Macros,
  type MenuNutrition as Nutrition,
  type MenuNutritionUpdate,
} from "../api/nutrition";
import { apiErrorMessage } from "../api/http";
import "./MenuNutrition.css";

/**
 * 메뉴의 1인분 열량 설정(D-082). 메뉴 수정 폼 안에 둔다 — 연결 식당과 같은 이유로, 사용자가
 * 메뉴를 손보러 오는 자리가 이미 그 폼이다.
 *
 * 메뉴 이름은 사용자가 짓기 때문에("회사 앞 김찌") 기준 음식을 자동으로 정하지 않는다. 이름으로
 * 후보를 찾아 주고 사람이 고른다. 마땅한 것이 없으면 직접 적는다.
 *
 * 저장하면 메뉴 버전이 오른다. `onVersionChange`로 바깥 폼에 새 버전을 알려야 그 폼의 저장이
 * "다른 곳에서 먼저 수정했다"로 막히지 않는다.
 */
export default function MenuNutrition({ menuId, menuName, onVersionChange }: {
  menuId: number; menuName: string; onVersionChange: (version: number) => void;
}) {
  const queryClient = useQueryClient();
  const id = useId();
  const nutritionQuery = useQuery({
    queryKey: ["menu-nutrition", menuId],
    queryFn: () => fetchMenuNutrition(menuId),
  });
  const [mode, setMode] = useState<"view" | "search" | "custom">("view");
  const [message, setMessage] = useState("");

  const save = useMutation({
    mutationFn: (body: MenuNutritionUpdate) => updateMenuNutrition(menuId, body),
    onSuccess: (data) => {
      queryClient.setQueryData(["menu-nutrition", menuId], data);
      onVersionChange(data.version);
      // 기록 목록의 끼니별 열량과 열량 요약이 이 값으로 계산된다.
      void queryClient.invalidateQueries({ queryKey: ["history"] });
      setMessage(data.kcalPerServing == null ? "열량을 지웠어요." : `1인분 약 ${data.kcalPerServing}kcal로 정했어요.`);
      setMode("view");
    },
  });

  const current = nutritionQuery.data;
  return (
    <section className="menu-nutrition" aria-labelledby={`${id}-title`}>
      <h3 id={`${id}-title`}>열량</h3>
      {nutritionQuery.isPending && <p role="status">열량을 불러오는 중…</p>}
      {nutritionQuery.isError && <p className="error" role="alert">{apiErrorMessage(nutritionQuery.error)}</p>}
      {current && <p>{describe(current)}</p>}
      {current && macroLine(current) && <p className="card-muted-hint">{macroLine(current)}</p>}
      <p role="status">{message}</p>
      {save.isError && <p className="error" role="alert">{apiErrorMessage(save.error)}</p>}

      {current && mode === "view" && (
        <div className="card-actions">
          <button type="button" onClick={() => { setMessage(""); setMode("search"); }}>기준 음식에서 찾기</button>
          <button type="button" onClick={() => { setMessage(""); setMode("custom"); }}>직접 적기</button>
          {current.kcalPerServing != null && (
            <button type="button" aria-disabled={save.isPending || undefined}
              onClick={() => { if (!save.isPending) save.mutate({}); }}>열량 지우기</button>
          )}
        </div>
      )}
      {mode === "search" && <ReferenceSearch initial={menuName} busy={save.isPending}
        onPick={(reference) => save.mutate({ foodReferenceId: reference.id })} onCancel={() => setMode("view")} />}
      {mode === "custom" && <CustomKcal initial={current ?? null} busy={save.isPending}
        onSave={(body) => save.mutate(body)} onCancel={() => setMode("view")} />}
    </section>
  );
}

function describe(nutrition: Nutrition): string {
  if (nutrition.kcalPerServing == null) return "아직 열량을 모르는 메뉴예요. 정해 두면 식사 기록에 열량이 합산돼요.";
  if (nutrition.foodReference) {
    const grams = nutrition.foodReference.servingGrams;
    return `1인분 약 ${nutrition.kcalPerServing}kcal · 기준: ${nutrition.foodReference.name}${grams ? ` (${grams}g)` : ""}`;
  }
  return `1인분 약 ${nutrition.kcalPerServing}kcal · 직접 적은 값`;
}

const MACRO_FIELDS = [
  { key: "carbsG", label: "탄수화물" },
  { key: "proteinG", label: "단백질" },
  { key: "fatG", label: "지방" },
  { key: "fiberG", label: "식이섬유" },
] as const;

/** 1인분 영양소 한 줄. 탄단지를 모르면 null — 식이섬유만 따로 아는 경우는 없다(열량·탄단지와 같이 온다). */
function macroLine(values: Partial<Macros>): string | null {
  if (values.carbsG == null || values.proteinG == null || values.fatG == null) return null;
  return MACRO_FIELDS.filter((field) => values[field.key] != null)
    .map((field) => `${field.label} ${values[field.key]}g`).join(" · ");
}

function ReferenceSearch({ initial, busy, onPick, onCancel }: {
  initial: string; busy: boolean; onPick: (reference: FoodReference) => void; onCancel: () => void;
}) {
  const id = useId();
  const [input, setInput] = useState(initial.slice(0, 50));
  const [query, setQuery] = useState(initial.trim().slice(0, 50));
  const results = useQuery({
    queryKey: ["food-references", query],
    queryFn: () => searchFoodReferences(query),
    enabled: query.length > 0,
  });
  return (
    <div className="menu-nutrition-search">
      <label htmlFor={`${id}-q`}>기준 음식 이름</label>
      <div className="menu-nutrition-row">
        <input id={`${id}-q`} type="search" value={input} maxLength={50}
          onChange={(event) => setInput(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== "Enter") return;
            event.preventDefault();
            if (event.nativeEvent.isComposing || event.keyCode === 229) return;
            setQuery(input.trim());
          }} />
        <button type="button" onClick={() => setQuery(input.trim())}>검색</button>
        <button type="button" onClick={onCancel}>취소</button>
      </div>
      {results.isFetching && <p role="status">찾는 중…</p>}
      {results.isError && <p className="error" role="alert">{apiErrorMessage(results.error)}</p>}
      {results.isSuccess && results.data.length === 0 && (
        <p role="status">찾는 음식이 없어요. 더 짧게 검색하거나 직접 적어 주세요.</p>
      )}
      {results.isSuccess && results.data.length > 0 && (
        <ul className="menu-nutrition-results" role="list" aria-label="기준 음식 검색 결과">
          {results.data.map((reference) => (
            <li key={reference.id}>
              <button type="button" aria-disabled={busy || undefined}
                onClick={() => { if (!busy) onPick(reference); }}>
                {reference.name} · 1인분 약 {reference.kcalPerServing}kcal
                {reference.servingGrams ? ` (${reference.servingGrams}g)` : ""}
                {macroLine(reference) && <span className="menu-nutrition-macros">{macroLine(reference)}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
      <p className="card-muted-hint">출처: 식품의약품안전처 식품영양성분 데이터베이스. 식당마다 양이 달라 추정치예요.</p>
    </div>
  );
}

/** 영양소 칸 한 개: 비우거나, 0~999.9를 소수점 한 자리까지. 서버 검증과 같다. */
const MACRO_PATTERN = /^\d{1,3}(\.\d)?$/;

function CustomKcal({ initial, busy, onSave, onCancel }: {
  initial: Nutrition | null; busy: boolean; onSave: (body: MenuNutritionUpdate) => void; onCancel: () => void;
}) {
  const id = useId();
  const [value, setValue] = useState(initial?.kcalPerServing != null ? String(initial.kcalPerServing) : "");
  const [macros, setMacros] = useState<Record<keyof Macros, string>>(() => ({
    carbsG: initial?.carbsG != null ? String(initial.carbsG) : "",
    proteinG: initial?.proteinG != null ? String(initial.proteinG) : "",
    fatG: initial?.fatG != null ? String(initial.fatG) : "",
    fiberG: initial?.fiberG != null ? String(initial.fiberG) : "",
  }));
  const kcal = Number(value);
  const kcalValid = /^\d+$/.test(value) && kcal >= 1 && kcal <= 5000;
  const badField = (key: keyof Macros) => macros[key] !== "" && !MACRO_PATTERN.test(macros[key]);
  const trio = [macros.carbsG, macros.proteinG, macros.fatG];
  // 탄단지는 셋을 함께 적거나 모두 비운다 — 하나만 있으면 비율을 그릴 수 없다.
  const partial = trio.some((v) => v !== "") && trio.some((v) => v === "");
  const valid = kcalValid && !MACRO_FIELDS.some((field) => badField(field.key)) && !partial;

  const submit = () => {
    if (!valid || busy) return;
    const body: MenuNutritionUpdate = { kcalPerServing: kcal };
    for (const field of MACRO_FIELDS) {
      if (macros[field.key] !== "") body[field.key] = Number(macros[field.key]);
    }
    onSave(body);
  };
  const onEnter = (event: KeyboardEvent) => {
    if (event.key === "Enter") { event.preventDefault(); submit(); }
  };

  return (
    // 바깥 메뉴 수정 폼 안에 들어가므로 <form>을 중첩하지 않는다. Enter는 직접 받는다.
    <div className="menu-nutrition-custom" role="group" aria-labelledby={`${id}-label`}>
      <label id={`${id}-label`} htmlFor={`${id}-kcal`}>1인분 열량 (kcal)</label>
      <input id={`${id}-kcal`} type="number" inputMode="numeric" min={1} max={5000} step={1} value={value}
        aria-invalid={value !== "" && !kcalValid} aria-describedby={`${id}-help`}
        onChange={(event) => setValue(event.target.value)} onKeyDown={onEnter} />
      <p id={`${id}-help`}>1~5,000 사이의 정수로 적어 주세요.</p>
      <fieldset className="menu-nutrition-macro-fields" aria-describedby={`${id}-macro-help`}>
        <legend>1인분 영양소 (선택, g)</legend>
        {MACRO_FIELDS.map((field) => (
          <label key={field.key}>
            {field.label}
            <input type="number" inputMode="decimal" min={0} max={999.9} step={0.1} value={macros[field.key]}
              aria-invalid={badField(field.key) || (partial && field.key !== "fiberG" && macros[field.key] === "")}
              onChange={(event) => setMacros((prev) => ({ ...prev, [field.key]: event.target.value }))}
              onKeyDown={onEnter} />
          </label>
        ))}
        <p id={`${id}-macro-help`} className={partial ? "error" : undefined}>
          {partial ? "탄수화물·단백질·지방은 함께 적거나 모두 비워 주세요."
            : "포장지·식당 안내문에 있으면 적어 두세요. 식이섬유는 비워도 돼요."}
        </p>
      </fieldset>
      <div className="card-actions">
        <button type="button" aria-disabled={!valid || busy || undefined} onClick={submit}>
          {busy ? "저장 중…" : "열량 저장"}
        </button>
        <button type="button" onClick={onCancel}>취소</button>
      </div>
    </div>
  );
}
