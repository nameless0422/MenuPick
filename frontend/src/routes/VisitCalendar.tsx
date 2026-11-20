import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { fetchHistoryCalendar } from "../api/history";
import { apiErrorMessage as errorMessage } from "../api/http";
import { calendarDays, currentKstMonth, EARLIEST_CALENDAR_MONTH, groupCalendarEntries, shiftMonth } from "./visitCalendarUtils";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

export default function VisitCalendar({ initialMonth }: { initialMonth?: string }) {
  const currentMonth = currentKstMonth();
  const [month, setMonth] = useState(initialMonth ?? currentMonth);
  const query = useQuery({ queryKey: ["history-calendar", month], queryFn: ({ signal }) => fetchHistoryCalendar(month, signal) });
  const entriesByDay = groupCalendarEntries(query.data?.entries ?? []);
  const [year, monthNumber] = month.split("-").map(Number);
  const monthLabel = `${year}년 ${monthNumber}월`;
  // 좁은 화면에서는 칸 안에 목록을 넣지 않고 날짜를 눌러 아래에서 본다(가로 스크롤 없이 7칸이 폭에 맞는다).
  // 고른 날이 없으면 그달 기록이 있는 마지막 날을 보인다.
  const [picked, setPicked] = useState<{ month: string; day: number } | null>(null);
  const daysWithEntries = [...entriesByDay.keys()].sort((a, b) => a - b);
  const selectedDay = picked?.month === month && entriesByDay.has(picked.day)
    ? picked.day : daysWithEntries[daysWithEntries.length - 1];
  const selectedEntries = selectedDay != null ? entriesByDay.get(selectedDay) ?? [] : [];
  return (
    <section className="visit-calendar" aria-labelledby="visit-calendar-heading">
      <div className="visit-calendar-heading-row">
        <div><h2 id="visit-calendar-heading">방문 기록 달력</h2><p>‘방문했어요’를 누른 날짜를 월별로 모아봅니다.</p></div>
        <div className="calendar-navigation" role="group" aria-label="달력 월 이동">
          <button type="button" onClick={() => setMonth(shiftMonth(month, -1))} disabled={month <= EARLIEST_CALENDAR_MONTH}>이전 달</button>
          <strong aria-live="polite">{monthLabel}</strong>
          <button type="button" onClick={() => setMonth(shiftMonth(month, 1))} disabled={month >= currentMonth}>다음 달</button>
        </div>
      </div>
      {query.isPending && <p role="status">{monthLabel} 방문 기록을 불러오는 중…</p>}
      {query.isError && <p className="error" role="alert">달력을 불러오지 못했습니다. {errorMessage(query.error)}</p>}
      {query.isSuccess && query.data.entries.length === 0 && <p>{monthLabel}에 표시할 방문 기록이 없어요.</p>}
      {query.data?.truncated && <p className="calendar-notice" role="status">기록이 많아 최근 500개만 표시합니다.</p>}
      {query.isSuccess && <div className="calendar-scroll"><table className="calendar-table">
        <caption>{monthLabel} 방문했어요 기록</caption>
        <thead><tr>{WEEKDAYS.map((day) => <th key={day} scope="col">{day}</th>)}</tr></thead>
        <tbody>{calendarDays(month).map((week, weekIndex) => <tr key={weekIndex}>{week.map((day, dayIndex) => day == null
          ? <td key={`empty-${dayIndex}`} className="calendar-outside" aria-hidden="true" />
          : <td key={day} aria-label={`${monthNumber}월 ${day}일, 방문 기록 ${entriesByDay.get(day)?.length ?? 0}개`}>
              <time dateTime={`${month}-${String(day).padStart(2, "0")}`} className="calendar-day">{day}</time>
              {entriesByDay.has(day) && (
                <button type="button" className="calendar-day-pick" aria-pressed={selectedDay === day}
                  aria-label={`${monthNumber}월 ${day}일 방문 기록 ${entriesByDay.get(day)!.length}개 보기`}
                  onClick={() => setPicked({ month, day })}>
                  <span aria-hidden="true">{entriesByDay.get(day)!.length}</span>
                </button>
              )}
              {entriesByDay.has(day) && <ul role="list">{entriesByDay.get(day)!.map((entry) => <li key={entry.id}>
                <strong>{entry.menuName ?? "삭제된 메뉴"}</strong><span>{entry.restaurantName ?? "삭제되었거나 기록되지 않은 식당"}</span>
              </li>)}</ul>}
            </td>)}</tr>)}</tbody>
      </table></div>}
      {query.isSuccess && selectedDay != null && (
        <div className="calendar-day-detail" aria-live="polite">
          <h3>{monthNumber}월 {selectedDay}일</h3>
          <ul role="list">{selectedEntries.map((entry) => <li key={entry.id}>
            <strong>{entry.menuName ?? "삭제된 메뉴"}</strong><span>{entry.restaurantName ?? "삭제되었거나 기록되지 않은 식당"}</span>
          </li>)}</ul>
        </div>
      )}
    </section>
  );
}
