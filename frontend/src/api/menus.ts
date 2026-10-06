import { http, unwrap, type ApiResponse } from "./http";

export interface TagSummary {
  id: number;
  name: string;
}

export interface MenuSummary {
  id: number;
  name: string;
  weight: number;
  isExcluded: boolean;
  categories: string[];
  tags: TagSummary[];
  /**
   * 이 시각까지 추천에서 쉰다(KST LocalDateTime). 쉬지 않으면 null.
   *
   * **값이 있다고 쉬는 중인 것이 아니다.** 서버는 기간이 지나도 이 값을 지우지 않으므로,
   * 판정은 지금과의 비교다 — `menuPause.ts`의 `isPaused`를 쓴다.
   *
   * 선택 필드로 둔 것은 서버가 안 보낸다는 뜻이 아니다(항상 보낸다). 이 키를 모르는 응답을
   * 받아도 화면이 "쉬지 않음"으로 읽고 계속 돌게 하려는 것이다 — 배포 중 섞이는 순간이 있다.
   */
  pausedUntil?: string | null;
}

export interface MenuDetail extends MenuSummary {
  memo: string | null;
  createdAt: string;
  updatedAt: string;
  /**
   * 낙관적 락 버전. 수정 요청에 **그대로 되돌려 보내야** 한다 — 서버는 이 값으로
   * "내가 이 화면을 그린 뒤 누가 먼저 고쳤는가"를 판정한다. 값을 빼면 400이고,
   * 오래된 값을 보내면 409 CONCURRENT_MODIFICATION이다.
   */
  version: number;
}

export interface MenuListResponse {
  menus: MenuSummary[];
  nextCursor: number | null;
  hasNext: boolean;
}

export interface DeletedMenuSummary {
  id: number;
  name: string;
  deletedAt: string;
  version: number;
}

export interface DeletedMenuListResponse {
  menus: DeletedMenuSummary[];
  nextCursor: string | null;
  hasNext: boolean;
}

export async function fetchDeletedMenus(cursor?: string, size = 20) {
  const res = await http.get<ApiResponse<DeletedMenuListResponse>>("/api/v1/menus/trash", {
    params: { cursor, size },
  });
  return unwrap(res);
}

export async function restoreMenu(menuId: number, version: number) {
  await http.post<ApiResponse<null>>(`/api/v1/menus/${menuId}/restore`, { version });
}

export interface MenuCreateRequest {
  name: string;
  memo?: string;
  weight: number;
  categories: string[];
  tagIds: number[];
}

export interface BulkMenuPreview {
  entries: { line: number; name: string; status: "ADD" | "EMPTY" | "DUPLICATE" | "EXISTING" | "INVALID" }[];
  addCount: number;
  hasInvalid: boolean;
}

export async function previewBulkMenus(names: string[]) {
  const res = await http.post<ApiResponse<BulkMenuPreview>>("/api/v1/menus/bulk/preview", { names });
  return unwrap(res);
}

export async function createBulkMenus(names: string[]) {
  const res = await http.post<ApiResponse<{ createdCount: number }>>("/api/v1/menus/bulk", { names });
  return unwrap(res);
}

export interface MenuUpdateRequest extends MenuCreateRequest {
  isExcluded: boolean;
  // 화면을 그릴 때 받은 MenuDetail.version을 그대로 싣는다.
  version: number;
}

export interface MenuExclusionEntry {
  menuId: number;
  excluded: boolean;
}

/**
 * 추천 제외를 한 번에 바꾼다. **담긴 메뉴만** 바뀌고 나머지는 그대로다(전체 교체가 아니다).
 * 한 개씩 22번 보내면 중간에 하나가 실패했을 때 절반만 반영된 상태가 남는다.
 */
export async function batchUpdateExclusions(entries: MenuExclusionEntry[]) {
  await http.patch<ApiResponse<null>>("/api/v1/menus/exclusions", { entries });
}

export async function fetchMenus(cursor?: number, size = 20, keyword?: string) {
  const res = await http.get<ApiResponse<MenuListResponse>>("/api/v1/menus", {
    params: { cursor, size, keyword },
  });
  return unwrap(res);
}

export async function fetchMenu(menuId: number) {
  const res = await http.get<ApiResponse<MenuDetail>>(`/api/v1/menus/${menuId}`);
  return unwrap(res);
}

export async function createMenu(request: MenuCreateRequest) {
  const res = await http.post<ApiResponse<MenuDetail>>("/api/v1/menus", request);
  return unwrap(res);
}

export async function updateMenu(menuId: number, request: MenuUpdateRequest) {
  const res = await http.put<ApiResponse<MenuDetail>>(`/api/v1/menus/${menuId}`, request);
  return unwrap(res);
}

export async function deleteMenu(menuId: number) {
  await http.delete<ApiResponse<null>>(`/api/v1/menus/${menuId}`);
}

export async function toggleExclude(menuId: number, exclude: boolean) {
  await http.patch<ApiResponse<null>>(`/api/v1/menus/${menuId}/exclude`, null, {
    params: { exclude },
  });
}

/**
 * 메뉴를 `days`일 동안 추천에서 쉬게 한다(서버 상한 90일).
 *
 * 추천 제외(`toggleExclude`)와 다른 점은 **되돌리는 방식**이다. 제외는 사람이 풀어야 하고,
 * 쉬기는 시각이 지나면 저절로 풀린다. 응답으로 바뀐 메뉴가 와서 언제까지인지 다시 묻지 않는다.
 */
export async function pauseMenu(menuId: number, days: number) {
  const res = await http.patch<ApiResponse<MenuDetail>>(
    `/api/v1/menus/${menuId}/pause`,
    { days },
  );
  return unwrap(res);
}

/** 쉬는 중인 메뉴를 지금 깨운다. 쉬지 않던 메뉴에 불러도 성공한다. */
export async function resumeMenu(menuId: number) {
  const res = await http.delete<ApiResponse<MenuDetail>>(`/api/v1/menus/${menuId}/pause`);
  return unwrap(res);
}
