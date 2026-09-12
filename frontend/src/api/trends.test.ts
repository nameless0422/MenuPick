import { afterEach, expect, it } from "vitest";
import type { AxiosResponse, InternalAxiosRequestConfig } from "axios";
import { http } from "./http";
import { fetchTrends } from "./trends";

const realAdapter = http.defaults.adapter;

afterEach(() => {
  http.defaults.adapter = realAdapter;
});

it("GET /api/v1/trends 응답 봉투와 DTO를 그대로 돌려준다", async () => {
  let request: InternalAxiosRequestConfig | undefined;
  const data = {
    status: "READY" as const,
    categories: [{ label: "한식", userCount: 12, rankOrder: 1 }],
    menus: [{ label: "김치찌개", userCount: 9, rankOrder: 1 }],
    windowDays: 7,
    minUsers: 5,
    maxLabels: 10,
    computedAt: "2026-09-13T11:00:00",
  };
  http.defaults.adapter = async (config) => {
    request = config;
    return {
      data: { success: true, data },
      status: 200,
      statusText: "OK",
      headers: {},
      config,
    } as AxiosResponse;
  };

  await expect(fetchTrends()).resolves.toEqual(data);
  expect(request?.method).toBe("get");
  expect(request?.url).toBe("/api/v1/trends");
});
