import { http, unwrap, type ApiResponse } from "./http";

export async function fetchDefaultExcludedTagIds() {
  const res = await http.get<ApiResponse<number[]>>("/api/v1/pick/preferences");
  return unwrap(res);
}

export async function updateDefaultExcludedTagIds(defaultExcludedTagIds: number[]) {
  const res = await http.put<ApiResponse<number[]>>("/api/v1/pick/preferences", {
    defaultExcludedTagIds,
  });
  return unwrap(res);
}
