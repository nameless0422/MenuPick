import { useId, useState } from "react";
import { Link } from "react-router-dom";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { MEAL_SLOT_LABELS, MEAL_SLOTS } from "../api/history";
import { fetchNutritionSummary } from "../api/nutrition";
import { apiErrorMessage } from "../api/http";
import NutritionChart from "./NutritionChart";
import { dayLabel } from "./nutritionChartScale";

/** 볼 수 있는 기간. 서버 상한(31일) 안이다. */
const PERIODS = [7, 30] as const;
type Period = (typeof PERIODS)[number];

const kcal = (value: number) => `약 ${value.toLocaleString("ko-KR")}kcal`;

/**
 * 열량 기록(D-082, D-083). 기록 화면의 개요 칸에 둔다.
 *
 * 목표 칼로리나 "초과" 같은 판정은 하지 않는다. 이 앱은 무엇을 먹을지 고르는 도구이고, 메뉴 이름은
 * 사용자가 지으며 식당마다 양이 다르다 — 정확해 보이는 장치를 붙이면 추정치가 판정이 된다.
 */
export default function NutritionSummary() {
  const headingId = useId();
  const [period, setPeriod] = useState<Period>(7);
  const query = useQuery({
    queryKey: ["history", "nutrition", period],
    queryFn: () => fetchNutritionSummary(period),
    // 기간을 바꾸는 동안 섹션이 사라졌다 나타나지 않게 이전 값을 둔다.
    placeholderData: keepPreviousData,
  });
  if (query.isPending) return null;
  if (query.isError) {
    return (
      <section className="card nutrition-summary" aria-labelledby={headingId}>
        <h2 id={headingId}>열량 기록</h2>
        <p className="error" role="alert">{apiErrorMessage(query.error)}</p>
      </section>
    );
  }
  const { periodDays, days, slots, knownMeals, unknownMeals, averageKcal, truncated } = query.data;

  return (
    <section className="card nutrition-summary" aria-labelledby={headingId} aria-busy={query.isFetching}>
      <div className="nutrition-summary-head">
        <h2 id={headingId}>열량 기록</h2>
        <div className="nutrition-period" role="group" aria-label="열량 기간">
          {PERIODS.map((option) => (
            <button key={option} type="button" aria-pressed={period === option} aria-label={`열량 ${option}일`}
              onClick={() => setPeriod(option)}>
              {option}일
            </button>
          ))}
        </div>
      </div>
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
            최근 {periodDays}일 · 하루 평균 <strong>{kcal(averageKcal ?? 0)}</strong>
            <span className="eating-summary-count"> (기록 있는 날 기준, 추정치)</span>
          </p>
          <NutritionChart days={days} averageKcal={averageKcal} />
          <ul className="chip-row" role="list" aria-label="끼니별 열량">
            {slots.filter((slot) => slot.meals > 0).map((slot) => (
              <li key={slot.slot} className="chip">
                {MEAL_SLOT_LABELS[slot.slot]} <span className="eating-summary-count">{kcal(slot.kcal)}</span>
              </li>
            ))}
          </ul>
          <details className="nutrition-table">
            <summary>숫자로 보기</summary>
            <div className="nutrition-table-scroll">
              <table>
                <caption>최근 {periodDays}일 하루 열량 (kcal, 추정치)</caption>
                <thead>
                  <tr>
                    <th scope="col">날짜</th>
                    {MEAL_SLOTS.map((slot) => <th key={slot} scope="col">{MEAL_SLOT_LABELS[slot]}</th>)}
                    <th scope="col">합계</th>
                    <th scope="col">모름</th>
                  </tr>
                </thead>
                <tbody>
                  {days.map((day) => (
                    <tr key={day.date}>
                      <th scope="row">{dayLabel(day.date)}</th>
                      {day.meals === 0 ? <td colSpan={MEAL_SLOTS.length + 2}>기록 없음</td> : (
                        <>
                          {MEAL_SLOTS.map((slot) => {
                            const value = day.slots?.find((s) => s.slot === slot)?.kcal ?? 0;
                            return <td key={slot}>{value > 0 ? value.toLocaleString("ko-KR") : "–"}</td>;
                          })}
                          <td>{day.kcal.toLocaleString("ko-KR")}</td>
                          <td>{day.unknownMeals > 0 ? `${day.unknownMeals}끼` : "–"}</td>
                        </>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
          {unknownMeals > 0 && (
            <p className="card-muted-hint">
              열량을 모르는 끼니 {unknownMeals}번은 합계에서 빠졌어요. <Link to="/menus">메뉴에 열량 정하기 →</Link>
            </p>
          )}
          {truncated && <p className="card-muted-hint">기록이 많아 일부만 합산했어요.</p>}
        </>
      )}
    </section>
  );
}
