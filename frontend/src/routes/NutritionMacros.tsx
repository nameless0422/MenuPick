import { useId } from "react";
import { Link } from "react-router-dom";
import type { MacroSummary } from "../api/nutrition";
import { energyShares } from "./nutritionChartScale";

const MACROS = [
  { key: "carbsG", label: "탄수화물", className: "nutrition-macro-carbs" },
  { key: "proteinG", label: "단백질", className: "nutrition-macro-protein" },
  { key: "fatG", label: "지방", className: "nutrition-macro-fat" },
] as const;

/** 탄단지와 따로 세는 영양소. 예전 서버는 당류·나트륨을 주지 않는다 — 그때는 줄을 두지 않는다("모름"과 다르다). */
function extrasOf(macros: MacroSummary) {
  const extras = [{ label: "식이섬유", value: macros.fiberG, unit: "g", meals: macros.fiberMeals }];
  if (macros.sugarsMeals != null) {
    extras.push({ label: "당류", value: macros.sugarsG ?? null, unit: "g", meals: macros.sugarsMeals });
  }
  if (macros.sodiumMeals != null) {
    extras.push({ label: "나트륨", value: macros.sodiumMg ?? null, unit: "mg", meals: macros.sodiumMeals });
  }
  return extras;
}

/**
 * 탄단지·식이섬유 하루 평균(D-084). 당류·나트륨(D-085)도 같은 자리에 둔다.
 *
 * 비율은 g이 아니라 열량 기준이다(탄수화물·단백질 4kcal/g, 지방 9kcal/g) — 흔히 말하는 "탄단지 비율"이
 * 그것이다. 권장 비율·하루 권장량과 견주는 판정은 하지 않는다(D-082와 같은 이유).
 */
export default function NutritionMacros({ macros, eatenMeals }: { macros: MacroSummary | undefined; eatenMeals: number }) {
  const headingId = useId();
  if (!macros) return null;
  const known = macros.meals > 0 && macros.carbsG != null && macros.proteinG != null && macros.fatG != null;
  const shares = known ? energyShares(macros.carbsG!, macros.proteinG!, macros.fatG!) : null;
  const extras = extrasOf(macros);
  const anyExtra = extras.some((extra) => extra.meals > 0 && extra.value != null);

  return (
    <section className="nutrition-macros" aria-labelledby={headingId}>
      <h3 id={headingId}>탄수화물·단백질·지방</h3>
      {!known ? (
        <p className="card-muted-hint">
          탄단지를 아는 메뉴로 먹은 끼니가 없어요. <Link to="/menus">메뉴의 열량 칸</Link>에서 기준 음식을 고르거나
          직접 적으면 여기에 보여요.
        </p>
      ) : (
        <>
          <p className="settings-desc">
            하루 평균 · <span className="eating-summary-count">먹은 {eatenMeals}끼 중 {macros.meals}끼 기준, 추정치</span>
          </p>
          {shares && (
            // 막대는 눈으로 훑기 위한 것이다. 같은 값과 비율을 아래 목록이 글자로 말한다.
            <div className="nutrition-macro-bar" aria-hidden="true">
              {MACROS.map((macro, i) => shares[i] > 0 && (
                <span key={macro.key} className={macro.className} style={{ flexGrow: shares[i] }} />
              ))}
            </div>
          )}
        </>
      )}
      {(known || anyExtra) && (
        <ul className="nutrition-macro-list" role="list" aria-label="탄단지 하루 평균">
          {known && MACROS.map((macro, i) => (
            <li key={macro.key}>
              <i className={`nutrition-swatch ${macro.className}`} aria-hidden="true" />
              {macro.label} <strong>약 {macros[macro.key]}g</strong>
              {shares && <span className="eating-summary-count"> 열량의 {shares[i]}%</span>}
            </li>
          ))}
          {extras.map((extra) => (
            <li key={extra.label}>
              {extra.label}{" "}
              {extra.value != null && extra.meals > 0
                ? <><strong>약 {extra.value.toLocaleString("ko-KR")}{extra.unit}</strong>
                  {/* 탄단지와 끼니 수가 다르면 몇 끼 기준인지 밝힌다. */}
                  {(!known || extra.meals !== macros.meals) &&
                    <span className="eating-summary-count"> ({extra.meals}끼 기준)</span>}</>
                : <span className="eating-summary-count">아는 메뉴가 없어요</span>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
