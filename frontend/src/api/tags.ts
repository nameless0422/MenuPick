import { http, unwrap, type ApiResponse } from "./http";

export interface TagInfo {
  id: number;
  name: string;
  createdAt: string;
}

export async function searchTags(keyword: string) {
  const res = await http.get<ApiResponse<TagInfo[]>>("/api/v1/tags", {
    params: { keyword },
  });
  return unwrap(res);
}

export async function createTag(name: string) {
  const res = await http.post<ApiResponse<TagInfo>>("/api/v1/tags", { name });
  return unwrap(res);
}

export async function deleteTag(tagId: number) {
  await http.delete<ApiResponse<null>>(`/api/v1/tags/${tagId}`);
}
