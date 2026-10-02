import axios from "axios";
import { http, unwrap, type ApiResponse } from "./http";
import type { PickRequest } from "./pick";

export type PickAlternativeType =
  | "EXPAND_DISTANCE"
  | "CLEAR_CATEGORIES"
  | "CLEAR_CATEGORIES_AND_EXPAND_DISTANCE"
  | "DROP_RECENT_EXCLUSION";

export interface PickAlternative {
  type: PickAlternativeType;
  candidateCount: number;
  /** 바뀌는 조건만 담긴다. 없는 키는 "그대로"라는 뜻이다. */
  changes: { categories?: []; maxDistance?: number; clearRecentExclusion?: true };
}

export interface PickAlternativesResult {
  alternatives: PickAlternative[];
}

export function pickAlternativesEnabled() {
  return import.meta.env.VITE_PICK_ALTERNATIVES_ENABLED === "true";
}

/**
 * 응답에 올 수 있는 종류와 그 순서.
 *
 * 서버는 최근 제외를 먼저 제안한다 — 그게 범인일 때 거리를 넓히라는 조언은 사용자를 한 번
 * 더 헛걸음시키기 때문이다. 아래 순서가 그 약속이고, 응답이 이 순서를 어기면 거부한다.
 */
const TYPES: PickAlternativeType[] = [
  "DROP_RECENT_EXCLUSION",
  "EXPAND_DISTANCE",
  "CLEAR_CATEGORIES",
  "CLEAR_CATEGORIES_AND_EXPAND_DISTANCE",
];

/**
 * 종류별로 어떤 변경 키가 있어야 하고 없어야 하는가.
 *
 * 있어야 할 것만 보지 않고 <b>없어야 할 것</b>까지 본다. 화면은 받은 키를 그대로 조건에
 * 반영하므로, 섞여 들어온 키 하나가 사용자가 켜 둔 조건을 조용히 바꿔 버린다.
 */
const EXPECTED_CHANGES: Record<
  PickAlternativeType,
  { distance: boolean; categories: boolean; dropRecent: boolean }
> = {
  DROP_RECENT_EXCLUSION: { distance: false, categories: false, dropRecent: true },
  EXPAND_DISTANCE: { distance: true, categories: false, dropRecent: false },
  CLEAR_CATEGORIES: { distance: false, categories: true, dropRecent: false },
  CLEAR_CATEGORIES_AND_EXPAND_DISTANCE: { distance: true, categories: true, dropRecent: false },
};

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
  const expected = EXPECTED_CHANGES[type];
  if ((expected.distance && (!Number.isInteger(parsedChanges.maxDistance)
        || (parsedChanges.maxDistance as number) <= 0 || (parsedChanges.maxDistance as number) > 5000))
      || (!expected.distance && parsedChanges.maxDistance !== undefined)
      || (expected.categories && (!Array.isArray(parsedChanges.categories)
        || parsedChanges.categories.length !== 0))
      || (!expected.categories && parsedChanges.categories !== undefined)
      || (expected.dropRecent && parsedChanges.clearRecentExclusion !== true)
      || (!expected.dropRecent && parsedChanges.clearRecentExclusion !== undefined)) {
    throw new Error("대안 응답 형식이 올바르지 않습니다.");
  }
  return {
    type,
    candidateCount: item.candidateCount as number,
    changes: {
      ...(expected.categories && { categories: [] as [] }),
      ...(expected.distance && { maxDistance: parsedChanges.maxDistance as number }),
      ...(expected.dropRecent && { clearRecentExclusion: true as const }),
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
