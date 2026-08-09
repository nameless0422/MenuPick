import { http, type ApiResponse } from "./http";

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
}

export async function fetchMenus(cursor?: number, size = 20) {
  const res = await http.get<ApiResponse<MenuListResponse>>("/api/v1/menus", {
    params: { cursor, size },
  });
  return res.data.data!;
}

export async function fetchMenu(menuId: number) {
  const res = await http.get<ApiResponse<MenuDetail>>(`/api/v1/menus/${menuId}`);
  return res.data.data!;
}

export async function createMenu(request: MenuCreateRequest) {
  const res = await http.post<ApiResponse<MenuDetail>>("/api/v1/menus", request);
  return res.data.data!;
}

export async function updateMenu(menuId: number, request: MenuUpdateRequest) {
  const res = await http.put<ApiResponse<MenuDetail>>(`/api/v1/menus/${menuId}`, request);
  return res.data.data!;
}

export async function deleteMenu(menuId: number) {
  await http.delete<ApiResponse<null>>(`/api/v1/menus/${menuId}`);
}

export async function toggleExclude(menuId: number, exclude: boolean) {
  await http.patch<ApiResponse<null>>(`/api/v1/menus/${menuId}/exclude`, null, {
    params: { exclude },
  });
}
