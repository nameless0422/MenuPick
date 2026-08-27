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

export interface MenuCreateRequest {
  name: string;
  memo?: string;
  weight: number;
  categories: string[];
  tagIds: number[];
}

export interface MenuUpdateRequest extends MenuCreateRequest {
  isExcluded: boolean;
  // 화면을 그릴 때 받은 MenuDetail.version을 그대로 싣는다.
  version: number;
}

export async function fetchMenus(cursor?: number, size = 20) {
  const res = await http.get<ApiResponse<MenuListResponse>>("/api/v1/menus", {
    params: { cursor, size },
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
