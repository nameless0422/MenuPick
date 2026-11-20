import { useEffect, useRef, useState } from "react";
import { MEAL_SLOT_LABELS, MEAL_SLOTS } from "../api/history";
import type { DayNutrition } from "../api/nutrition";
import type { ChartMetric } from "./nutritionChartScale";

/** 세 그래프(막대·선·원)가 함께 쓰는 조각. */

export const MACRO_SERIES = [
  { key: "carbsG", label: "탄수화물", cls: "carbs" },
  { key: "proteinG", label: "단백질", cls: "protein" },
  { key: "fatG", label: "지방", cls: "fat" },
] as const;

export const UNIT: Record<ChartMetric, string> = { kcal: "kcal", macros: "g" };

export type Segment = { key: string; label: string | null; cls: string; value: number };

/** 그래프를 담은 상자의 너비. ResizeObserver가 없는 환경(테스트)에서는 fallback 그대로다. */
export function useWidth<T extends HTMLElement>(fallback: number) {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(fallback);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(([entry]) => {
      const next = Math.round(entry.contentRect.width);
      if (next > 0) setWidth(next);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}

/**
 * 날짜 하나의 조각들. 열량: 끼니별(예전 서버는 `slots`가 없으니 합계 한 조각). 탄단지: 영양소별 g.
 * 값이 0인 조각은 그리지 않는다.
 */
export function segmentsOf(day: DayNutrition, metric: ChartMetric): Segment[] {
  if (metric === "macros") {
    const macros = day.macros;
    if (!macros || macros.meals === 0) return [];
    return MACRO_SERIES.map((s) => ({ key: s.key, label: s.label, cls: s.cls, value: macros[s.key] ?? 0 }))
      .filter((segment) => segment.value > 0);
  }
  if (day.slots && day.slots.length > 0) {
    return MEAL_SLOTS.map((slot) => ({
      key: slot, label: MEAL_SLOT_LABELS[slot], cls: slot.toLowerCase(),
      value: day.slots!.find((s) => s.slot === slot)?.kcal ?? 0,
    })).filter((segment) => segment.value > 0);
  }
  return day.kcal > 0 ? [{ key: "total", label: null, cls: "total", value: day.kcal }] : [];
}

export function totalOf(day: DayNutrition, metric: ChartMetric): number {
  return metric === "kcal" ? day.kcal : segmentsOf(day, metric).reduce((sum, s) => sum + s.value, 0);
}

export const fmt = (value: number) => value.toLocaleString("ko-KR");
