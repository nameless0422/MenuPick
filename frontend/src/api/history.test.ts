import { afterEach, describe, expect, it } from "vitest";
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from "axios";
import { fetchHistories, fetchHistoryCalendar, validateHistoryCalendar, fetchHistoryCsv } from "./history";
import { http, apiErrorMessage } from "./http";

const realAdapter = http.defaults.adapter;
afterEach(() => { http.defaults.adapter = realAdapter; });

describe("픽 기록 조회 API", () => {
  it("방문 상태와 커서를 같은 요청에 보낸다", async () => {
    let sent: InternalAxiosRequestConfig | undefined;
    http.defaults.adapter = async (config) => {
      sent = config;
      return { data: { success: true, data: { histories: [], nextCursor: null, hasNext: false } },
        status: 200, statusText: "OK", headers: {}, config } as AxiosResponse;
    };

    await fetchHistories(42, 30, 20, false, "진주회관");
    expect(sent?.params).toEqual({ cursor: 42, days: 30, size: 20, visited: false, keyword: "진주회관" });
  });
});

describe("픽 기록 CSV API", () => {
  it("적용한 필터를 보내고 CSV 파일을 받는다", async () => {
    const blob = new Blob(["\uFEFF메뉴\r\n국수\r\n"], { type: "text/csv;charset=utf-8" });
    let sent: InternalAxiosRequestConfig | undefined;
    http.defaults.adapter = async (config) => {
      sent = config;
      return { data: blob, status: 200, statusText: "OK", headers: { "content-type": "text/csv;charset=UTF-8" }, config };
    };
    await expect(fetchHistoryCsv(30, true, "국수")).resolves.toBe(blob);
    expect(sent?.url).toBe("/api/v1/history/export");
    expect(sent?.params).toEqual({ days: 30, visited: true, keyword: "국수" });
    expect(sent?.responseType).toBe("blob");
  });

  it("JSON 오류 Blob을 풀어 서버 안내를 보여준다", async () => {
    const blob = new Blob([JSON.stringify({ message: "기간이나 검색 조건을 좁혀주세요." })], { type: "application/json" });
    http.defaults.adapter = async (config) => {
      throw new AxiosError("Request failed", "ERR_BAD_REQUEST", config, null, {
        data: blob, status: 400, statusText: "Bad Request", headers: { "content-type": "application/json" }, config,
      });
    };
    const error = await fetchHistoryCsv(7).catch((failure: unknown) => failure);
    expect(apiErrorMessage(error)).toBe("기간이나 검색 조건을 좁혀주세요.");
  });

  it("성공 상태여도 CSV가 아니면 파일을 만들지 않는다", async () => {
    http.defaults.adapter = async (config) => ({
      data: new Blob(["<html>error</html>"]), status: 200, statusText: "OK", headers: { "content-type": "text/html" }, config,
    });
    await expect(fetchHistoryCsv(7)).rejects.toThrow("기록 파일을 받지 못했습니다");
  });
});

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
