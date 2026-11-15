import { useId } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { MEAL_SLOT_LABELS } from "../api/history";
import { fetchNutritionSummary } from "../api/nutrition";
import { apiErrorMessage } from "../api/http";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

/** "2026-10-09" → "10/9 (금)". 날짜 문자열만 쓴다 — Date로 읽으면 브라우저 시간대에 따라 하루가 밀린다. */
function dayLabel(date: string): string {
  const [y, m, d] = date.split("-").map(Number);
  const weekday = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return `${m}/${d} (${WEEKDAYS[weekday]})`;
}

/**
 * 최근 7일 열량 추정(D-082). 기록 화면의 개요 칸에 둔다.
 *
 * 목표 칼로리나 "초과" 같은 판정은 하지 않는다. 이 앱은 무엇을 먹을지 고르는 도구이고, 메뉴 이름은
 * 사용자가 지으며 식당마다 양이 다르다 — 정확해 보이는 장치를 붙이면 추정치가 판정이 된다.
 */
export default function NutritionSummary() {
  const headingId = useId();
  const query = useQuery({ queryKey: ["history", "nutrition"], queryFn: () => fetchNutritionSummary() });
  if (query.isPending) return null;
  if (query.isError) {
    return (
      <section className="card nutrition-summary" aria-labelledby={headingId}>
        <h2 id={headingId}>열량 기록</h2>
        <p className="error" role="alert">{apiErrorMessage(query.error)}</p>
      </section>
    );
  }
  const { periodDays, days, slots, knownMeals, unknownMeals, averageKcal } = query.data;
  const eatenSlots = slots.filter((slot) => slot.meals > 0);
  const maxKcal = Math.max(1, ...days.map((day) => day.kcal));

  return (
    <section className="card nutrition-summary" aria-labelledby={headingId}>
      <h2 id={headingId}>열량 기록</h2>
      {knownMeals === 0 ? (
        <p className="card-muted-hint">
          {unknownMeals > 0
            ? <>최근 {periodDays}일에 먹은 {unknownMeals}끼 모두 열량을 모르는 메뉴예요. </>
            : <>최근 {periodDays}일에 먹은 기록이 없어요. </>}
          <Link to="/menus">메뉴에 열량을 정해 두면</Link> 끼니마다 합산돼요.
        </p>
      ) : (
        <>
          <p className="settings-desc">
            최근 {periodDays}일 · 하루 평균 <strong>약 {averageKcal}kcal</strong>
            <span className="eating-summary-count"> (기록 있는 날 기준, 추정치)</span>
          </p>
          <ul className="nutrition-days" role="list" aria-label={`최근 ${periodDays}일 하루 열량`}>
            {days.map((day) => (
              <li key={day.date}>
                <span className="nutrition-day-label">{dayLabel(day.date)}</span>
                {/* 막대는 눈으로 훑기 위한 장식이다. 값은 옆 글자가 말한다. */}
                <span className="nutrition-bar" aria-hidden="true">
                  <span style={{ width: `${Math.round((day.kcal / maxKcal) * 100)}%` }} />
                </span>
                <span className="nutrition-day-value">
                  {day.meals === 0 ? "기록 없음" : `약 ${day.kcal}kcal`}
                  {day.unknownMeals > 0 && ` · 모름 ${day.unknownMeals}끼`}
                </span>
              </li>
            ))}
          </ul>
          {eatenSlots.length > 0 && (
            <ul className="chip-row" role="list" aria-label="끼니별 열량">
              {eatenSlots.map((slot) => (
                <li key={slot.slot} className="chip">
                  {MEAL_SLOT_LABELS[slot.slot]} <span className="eating-summary-count">약 {slot.kcal}kcal</span>
                </li>
              ))}
            </ul>
          )}
          {unknownMeals > 0 && (
            <p className="card-muted-hint">
              열량을 모르는 끼니 {unknownMeals}번은 합계에서 빠졌어요. <Link to="/menus">메뉴에 열량 정하기 →</Link>
            </p>
          )}
        </>
      )}
    </section>
  );
}
