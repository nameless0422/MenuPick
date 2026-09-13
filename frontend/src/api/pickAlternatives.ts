import axios from "axios";
import { http, unwrap, type ApiResponse } from "./http";
import type { PickRequest } from "./pick";

export type PickAlternativeType =
  | "EXPAND_DISTANCE"
  | "CLEAR_CATEGORIES"
  | "CLEAR_CATEGORIES_AND_EXPAND_DISTANCE";

export interface PickAlternative {
  type: PickAlternativeType;
  candidateCount: number;
  changes: { categories?: []; maxDistance?: number };
}

export interface PickAlternativesResult {
  alternatives: PickAlternative[];
}

export function pickAlternativesEnabled() {
  return import.meta.env.VITE_PICK_ALTERNATIVES_ENABLED === "true";
}

const TYPES: PickAlternativeType[] = [
  "EXPAND_DISTANCE",
  "CLEAR_CATEGORIES",
  "CLEAR_CATEGORIES_AND_EXPAND_DISTANCE",
];

function parseAlternative(value: unknown): PickAlternative {
  if (!value || typeof value !== "object") throw new Error("대안 응답 형식이 올바르지 않습니다.");
  const item = value as Record<string, unknown>;
  const changes = item.changes;
  if (!TYPES.includes(item.type as PickAlternativeType)
      || !Number.isInteger(item.candidateCount) || (item.candidateCount as number) <= 0
      || !changes || typeof changes !== "object") {
    throw new Error("대안 응답 형식이 올바르지 않습니다.");
  }
  const parsedChanges = changes as Record<string, unknown>;
  const type = item.type as PickAlternativeType;
  const needsDistance = type !== "CLEAR_CATEGORIES";
  const needsCategories = type !== "EXPAND_DISTANCE";
  if ((needsDistance && (!Number.isInteger(parsedChanges.maxDistance)
        || (parsedChanges.maxDistance as number) <= 0 || (parsedChanges.maxDistance as number) > 5000))
      || (!needsDistance && parsedChanges.maxDistance !== undefined)
      || (needsCategories && (!Array.isArray(parsedChanges.categories)
        || parsedChanges.categories.length !== 0))
      || (!needsCategories && parsedChanges.categories !== undefined)) {
    throw new Error("대안 응답 형식이 올바르지 않습니다.");
  }
  return {
    type,
    candidateCount: item.candidateCount as number,
    changes: {
      ...(needsCategories && { categories: [] as [] }),
      ...(needsDistance && { maxDistance: parsedChanges.maxDistance as number }),
    },
  };
}

export async function requestPickAlternatives(request: PickRequest, signal?: AbortSignal) {
  const response = await http.post<ApiResponse<unknown>>("/api/v1/pick/alternatives", request, { signal });
  const data = unwrap(response);
  if (!data || typeof data !== "object" || !Array.isArray((data as Record<string, unknown>).alternatives)) {
    throw new Error("대안 응답 형식이 올바르지 않습니다.");
  }
  const alternatives = (data as { alternatives: unknown[] }).alternatives;
  if (alternatives.length > 2) throw new Error("대안 응답 형식이 올바르지 않습니다.");
  const parsed = alternatives.map(parseAlternative);
  const order = parsed.map((item) => TYPES.indexOf(item.type));
  if (new Set(parsed.map((item) => item.type)).size !== parsed.length
      || order.some((value, index) => index > 0 && value <= order[index - 1])) {
    throw new Error("대안 응답 형식이 올바르지 않습니다.");
  }
  return { alternatives: parsed };
}

export function isPickAlternativesUnavailable(error: unknown) {
  return axios.isAxiosError(error) && error.response?.status === 404;
}
