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

/**
 * 내 태그 전체. 설정의 태그 관리 화면이 쓴다.
 *
 * <p>{@link searchTags}를 빈 키워드로 부르는 것으로 대신할 수 없다 — 그쪽은 자동완성이라
 * 키워드가 비면 서버가 의도적으로 빈 목록을 준다.
 */
export async function fetchAllTags() {
  const res = await http.get<ApiResponse<TagInfo[]>>("/api/v1/tags", {
    params: { all: true },
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
