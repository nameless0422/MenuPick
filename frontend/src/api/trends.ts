import { http, unwrap, type ApiResponse } from "./http";

export type TrendStatus = "DISABLED" | "NOT_READY" | "STALE" | "READY";

export interface TrendEntry {
  label: string;
  /** 서로 다른 사용자 수다. 선택 횟수로 표시하면 안 된다. */
  userCount: number;
  rankOrder: number;
}

export interface TrendsResponse {
  categories: TrendEntry[];
  menus: TrendEntry[];
  status: TrendStatus;
  windowDays: number;
  minUsers: number;
  /** 서버 설정의 축별 응답 상한이며 계약상 1..20이다. */
  maxLabels: number;
  /** offset이 없는 Asia/Seoul LocalDateTime이다. */
  computedAt?: string | null;
}

export async function fetchTrends(): Promise<TrendsResponse> {
  const response = await http.get<ApiResponse<TrendsResponse>>("/api/v1/trends");
  return unwrap(response);
}
