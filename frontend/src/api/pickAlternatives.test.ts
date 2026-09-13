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

  it.each([
    { alternatives: [{ type: "UNKNOWN", candidateCount: 1, changes: {} }] },
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
