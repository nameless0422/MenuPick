import { http, unwrap, type ApiResponse } from "./http";
import type { PickResult } from "./pick";

/**
 * 상황별 빠른 픽 — 저장형 프리셋 (docs/PickPresetDesign.md).
 *
 * 서버에 좌표를 저장하지 않는다. 거리 조건은 "몇 m 이내"만 저장하고 기준점은 실행할 때마다
 * 브라우저에서 새로 받는다 — 회사에서 만든 "회사 점심"을 집에서 눌렀을 때 회사 주변을
 * 뽑지 않기 위해서다.
 */

/** 저장할 수 있는 거리 값. 자유 입력이 아니라 네 단계다(백엔드 PickPresetDistance와 같은 목록). */
export const PRESET_DISTANCES = [300, 500, 1000, 2000] as const;
export type PresetDistance = (typeof PRESET_DISTANCES)[number];

/** 사용자당 상한. 서버 응답의 `limit`과 같은 값이지만 화면이 미리 알아야 할 때가 있다. */
export const PRESET_LIMIT = 10;

export interface PickPreset {
  id: number;
  name: string;
  categories: string[];
  includeTagIds: number[];
  /**
   * **추가** 제외만이다. 사용자의 기본 제외 태그는 저장되지 않고 실행할 때마다 최신 값이
   * 합쳐진다 — 나중에 알레르기 태그를 추가해도 옛 프리셋이 그걸 모른 채 돌지 않게 하려는 것.
   */
  additionalExcludeTagIds: number[];
  maxDistance: number | null;
  /**
   * 참조하던 태그가 삭제돼 조건이 줄었다. 이 값이 true인 동안 **실행이 막힌다** —
   * 화면은 경고를 보여주고 편집으로 유도해야 한다.
   */
  needsReview: boolean;
  /** 수정·삭제·실행 요청에 그대로 실어 보낸다. 빼면 400, 오래되면 409다. */
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface PickPresetListResponse {
  presets: PickPreset[];
  limit: number;
}

export interface PickPresetSaveRequest {
  name: string;
  categories: string[];
  includeTagIds: number[];
  additionalExcludeTagIds: number[];
  maxDistance: number | null;
}

export interface PickPresetUpdateRequest extends PickPresetSaveRequest {
  version: number;
  /**
   * 검토 필요 상태를 푸는 명시적 승인. 태그가 지워진 프리셋은 이 값이 true여야 되살아난다 —
   * 사용자가 "지금 조건이 이게 맞다"고 확인하지 않은 채 다시 돌기 시작하는 것을 막는다.
   */
  acknowledgeRemovedTags?: boolean;
}

/** 실행 요청. **필터 override를 받지 않는다** — 저장된 조건 그대로 돈다. */
export interface PickPresetExecuteRequest {
  version: number;
  /** 프리셋에 거리 조건이 있을 때만 보낸다. 없는데 보내면 400이다. */
  latitude?: number;
  longitude?: number;
}

/** 서버가 **실제로 적용한** 조건. 미리보기가 보여준 값과 다를 수 있고, 이쪽이 권위 있다. */
export interface AppliedFilters {
  categories: string[];
  includeTagIds: number[];
  /** 기본 제외 + 추가 제외의 합집합. 실행 시점 값이다. */
  effectiveExcludeTagIds: number[];
  maxDistance: number | null;
}

export interface PickPresetExecutionResult {
  pick: PickResult;
  appliedFilters: AppliedFilters;
}

export async function fetchPickPresets() {
  const res = await http.get<ApiResponse<PickPresetListResponse>>("/api/v1/pick/presets");
  return unwrap(res);
}

export async function createPickPreset(request: PickPresetSaveRequest) {
  const res = await http.post<ApiResponse<PickPreset>>("/api/v1/pick/presets", request);
  return unwrap(res);
}

export async function updatePickPreset(id: number, request: PickPresetUpdateRequest) {
  const res = await http.put<ApiResponse<PickPreset>>(`/api/v1/pick/presets/${id}`, request);
  return unwrap(res);
}

/** 버전은 쿼리로 보낸다 — DELETE 본문은 프록시·클라이언트마다 취급이 달라 서버가 그렇게 받는다. */
export async function deletePickPreset(id: number, version: number) {
  await http.delete<ApiResponse<null>>(`/api/v1/pick/presets/${id}`, {
    params: { version },
  });
}

export async function executePickPreset(id: number, request: PickPresetExecuteRequest) {
  const res = await http.post<ApiResponse<PickPresetExecutionResult>>(
    `/api/v1/pick/presets/${id}/pick`,
    request,
  );
  return unwrap(res);
}
