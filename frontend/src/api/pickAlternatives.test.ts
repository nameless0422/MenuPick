import { afterEach, describe, expect, it } from "vitest";
import type { AxiosResponse, InternalAxiosRequestConfig } from "axios";
import { http } from "./http";
import { requestPickAlternatives } from "./pickAlternatives";

const realAdapter = http.defaults.adapter;
afterEach(() => { http.defaults.adapter = realAdapter; });

function respond(data: unknown, capture?: (config: InternalAxiosRequestConfig) => void) {
  http.defaults.adapter = async (config) => {
    capture?.(config);
    return { data: { success: true, data }, status: 200, statusText: "OK", headers: {}, config } as AxiosResponse;
  };
}

describe("requestPickAlternatives", () => {
  it("요청과 AbortSignal을 전달하고 유효한 응답만 반환한다", async () => {
    let sent: InternalAxiosRequestConfig | undefined;
    respond({ alternatives: [{ type: "EXPAND_DISTANCE", candidateCount: 3, changes: { maxDistance: 1000 } }] }, (config) => { sent = config; });
    const controller = new AbortController();
    await expect(requestPickAlternatives({ categories: ["한식"] }, controller.signal)).resolves.toEqual({
      alternatives: [{ type: "EXPAND_DISTANCE", candidateCount: 3, changes: { maxDistance: 1000 } }],
    });
    expect(sent?.url).toBe("/api/v1/pick/alternatives");
    expect(sent?.method).toBe("post");
    expect(sent?.signal).toBe(controller.signal);
  });

  it("최근 제외 끄기 대안을 그대로 돌려준다", async () => {
    respond({ alternatives: [
      { type: "DROP_RECENT_EXCLUSION", candidateCount: 5, changes: { clearRecentExclusion: true } },
      { type: "EXPAND_DISTANCE", candidateCount: 2, changes: { maxDistance: 1000 } },
    ] });
    await expect(requestPickAlternatives({ excludeRecentDays: 7 })).resolves.toEqual({
      alternatives: [
        { type: "DROP_RECENT_EXCLUSION", candidateCount: 5, changes: { clearRecentExclusion: true } },
        { type: "EXPAND_DISTANCE", candidateCount: 2, changes: { maxDistance: 1000 } },
      ],
    });
  });

  it.each([
    { alternatives: [{ type: "UNKNOWN", candidateCount: 1, changes: {} }] },
    // 종류와 맞지 않는 변경 키는 거부한다. 화면은 받은 키를 그대로 조건에 반영하므로,
    // 섞여 들어온 키 하나가 사용자가 켜 둔 조건을 조용히 바꿔 버린다.
    { alternatives: [{ type: "DROP_RECENT_EXCLUSION", candidateCount: 1, changes: { maxDistance: 1000 } }] },
    { alternatives: [{ type: "DROP_RECENT_EXCLUSION", candidateCount: 1, changes: {} }] },
    { alternatives: [{ type: "EXPAND_DISTANCE", candidateCount: 1, changes: { maxDistance: 1000, clearRecentExclusion: true } }] },
    // 순서도 계약이다 — 최근 제외가 범인일 때 거리를 먼저 권하면 한 번 더 헛걸음한다.
    { alternatives: [
      { type: "EXPAND_DISTANCE", candidateCount: 2, changes: { maxDistance: 1000 } },
      { type: "DROP_RECENT_EXCLUSION", candidateCount: 5, changes: { clearRecentExclusion: true } },
    ] },
    { alternatives: [{ type: "CLEAR_CATEGORIES", candidateCount: 0, changes: { categories: [] } }] },
    { alternatives: [{ type: "EXPAND_DISTANCE", candidateCount: 1, changes: { maxDistance: 9000 } }] },
    { alternatives: [
      { type: "CLEAR_CATEGORIES", candidateCount: 1, changes: { categories: [] } },
      { type: "EXPAND_DISTANCE", candidateCount: 1, changes: { maxDistance: 1000 } },
    ] },
  ])("잘못된 계약을 화면 상태로 흘려보내지 않는다", async (data) => {
    respond(data);
    await expect(requestPickAlternatives({})).rejects.toThrow("대안 응답 형식");
  });
});
