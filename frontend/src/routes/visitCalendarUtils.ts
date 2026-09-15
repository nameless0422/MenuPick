import type { HistoryCalendarEntry } from "../api/history";

export const EARLIEST_CALENDAR_MONTH = "2000-01";

export function currentKstMonth(now = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit" }).formatToParts(now);
  return `${parts.find((part) => part.type === "year")!.value}-${parts.find((part) => part.type === "month")!.value}`;
}

export function shiftMonth(month: string, amount: number): string {
  const [year, monthNumber] = month.split("-").map(Number);
  const shifted = new Date(Date.UTC(year, monthNumber - 1 + amount, 1));
  return `${shifted.getUTCFullYear()}-${String(shifted.getUTCMonth() + 1).padStart(2, "0")}`;
}

export function calendarDays(month: string): (number | null)[][] {
  const [year, monthNumber] = month.split("-").map(Number);
  const cells: (number | null)[] = Array(new Date(Date.UTC(year, monthNumber - 1, 1)).getUTCDay()).fill(null);
  const lastDay = new Date(Date.UTC(year, monthNumber, 0)).getUTCDate();
  for (let day = 1; day <= lastDay; day += 1) cells.push(day);
  while (cells.length % 7 !== 0) cells.push(null);
  return Array.from({ length: cells.length / 7 }, (_, index) => cells.slice(index * 7, index * 7 + 7));
}

export function groupCalendarEntries(entries: HistoryCalendarEntry[]) {
  const grouped = new Map<number, HistoryCalendarEntry[]>();
  for (const entry of entries) {
    const day = Number(entry.visitedAt.slice(8, 10));
    grouped.set(day, [...(grouped.get(day) ?? []), entry]);
  }
  return grouped;
}
