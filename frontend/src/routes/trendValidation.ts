import type { TrendEntry, TrendsResponse } from "../api/trends";

export const TREND_MAX_AGE_MS = 36 * 60 * 60 * 1000;
export const MAX_TREND_LABELS = 20;
export const PICK_CATEGORY_LIMIT = 20;

export function addTrendCategory(current: string[], category: string): string[] {
  return current.includes(category) || current.length >= PICK_CATEGORY_LIMIT
    ? current
    : [...current, category];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

/** 서버의 offset 없는 LocalDateTime을 Asia/Seoul 시각으로만 해석한다. */
export function trendComputedAtMillis(value: string | null | undefined): number | null {
  if (!value) return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/.exec(value);
  if (!match) return null;

  const [, yearText, monthText, dayText, hourText, minuteText, secondText = "0", fraction = ""] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText);
  if (
    year < 1000 || month < 1 || month > 12 || day < 1
    || day > new Date(Date.UTC(year, month, 0)).getUTCDate()
    || hour > 23 || minute > 59 || second > 59
  ) return null;

  const milliseconds = Number(fraction.slice(0, 3).padEnd(3, "0"));
  return Date.UTC(year, month - 1, day, hour - 9, minute, second, milliseconds);
}

function validEntry(value: unknown, maxLabelLength: number): value is TrendEntry {
  if (!isRecord(value)) return false;
  return typeof value.label === "string"
    && value.label.trim() === value.label
    && value.label.length > 0
    && value.label.length <= maxLabelLength
    && Number.isSafeInteger(value.userCount)
    && (value.userCount as number) >= 0
    && Number.isSafeInteger(value.rankOrder)
    && (value.rankOrder as number) >= 1;
}

/** 타입 선언만 믿지 않고 공개 집계 응답을 화면 경계에서 검증한다. */
export function validateTrendsResponse(value: unknown): TrendsResponse | null {
  if (!isRecord(value)) return null;
  const statuses = new Set(["DISABLED", "NOT_READY", "STALE", "READY"]);
  if (
    typeof value.status !== "string" || !statuses.has(value.status)
    || !Array.isArray(value.categories) || !value.categories.every((entry) => validEntry(entry, 20))
    || !Array.isArray(value.menus) || !value.menus.every((entry) => validEntry(entry, 50))
    || !Number.isSafeInteger(value.windowDays) || (value.windowDays as number) < 1
    || !Number.isSafeInteger(value.minUsers) || (value.minUsers as number) < 1
    || !Number.isSafeInteger(value.maxLabels) || (value.maxLabels as number) < 1
    || (value.maxLabels as number) > MAX_TREND_LABELS
  ) return null;

  const computedAt = value.computedAt;
  if (
    computedAt !== undefined && computedAt !== null
    && (typeof computedAt !== "string" || trendComputedAtMillis(computedAt) === null)
  ) return null;
  return value as unknown as TrendsResponse;
}
