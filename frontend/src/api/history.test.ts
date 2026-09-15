import { afterEach, describe, expect, it } from "vitest";
import type { AxiosResponse, InternalAxiosRequestConfig } from "axios";
import { fetchHistoryCalendar, validateHistoryCalendar } from "./history";
import { http } from "./http";

const realAdapter = http.defaults.adapter;
afterEach(() => { http.defaults.adapter = realAdapter; });

describe("방문 기록 달력 API", () => {
  it("month와 취소 신호를 보내고 응답을 검증한다", async () => {
    let sent: InternalAxiosRequestConfig | undefined;
    http.defaults.adapter = async (config) => {
      sent = config;
      return { data: { success: true, data: { month: "2024-02", entries: [{ id: 1, menuName: null, restaurantName: null, visitedAt: "2024-02-29T23:59:59.123" }], truncated: false } }, status: 200, statusText: "OK", headers: {}, config } as AxiosResponse;
    };
    const controller = new AbortController();
    await expect(fetchHistoryCalendar("2024-02", controller.signal)).resolves.toMatchObject({ month: "2024-02" });
    expect(sent?.url).toBe("/api/v1/history/calendar");
    expect(sent?.params).toEqual({ month: "2024-02" });
    expect(sent?.signal).toBe(controller.signal);
  });

  it.each([
    [{ month: "2024-03", entries: [], truncated: false }, "2024-02"],
    [{ month: "2024-02", entries: [{ id: 1, menuName: "메뉴", restaurantName: null, visitedAt: "2024-02-30T00:00:00" }], truncated: false }, "2024-02"],
    [{ month: "2024-02", entries: [{ id: 1, menuName: "메뉴", restaurantName: null, visitedAt: "2024-02-01T00:00:00Z" }], truncated: false }, "2024-02"],
    [{ month: "2024-02", entries: Array.from({ length: 501 }, (_, index) => ({ id: index + 1, menuName: null, restaurantName: null, visitedAt: "2024-02-01T00:00:00" })), truncated: true }, "2024-02"],
  ])("잘못된 응답을 거부한다", (data, month) => {
    expect(() => validateHistoryCalendar(data, month)).toThrow("방문 기록 달력 응답 형식");
  });
});
