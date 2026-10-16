import { expect, test } from "@playwright/test";
import { readFile } from "node:fs/promises";

test("선택한 조건의 다음 페이지 기록도 CSV로 받고 초과 오류는 파일로 저장하지 않는다", async ({ page }) => {
  let exports = 0;
  let nextPageReads = 0;
  let overLimit = false;
  let downloads = 0;
  page.on("download", () => { downloads += 1; });
  const csv = '\uFEFF"기록 ID","메뉴","메모"\r\n"5","국수","첫 페이지"\r\n"4","국수","아직 읽지 않은 기록, 줄바꿈\n둘째 줄"\r\n';
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const respond = (data: unknown) => route.fulfill({
      contentType: "application/json", body: JSON.stringify({ success: true, data }),
    });
    if (url.pathname === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (url.pathname === "/api/v1/history/summary") return respond({ periodDays: 30, picks: 0, eaten: 0, categories: [], menus: [], forgottenMenus: [] });
    if (url.pathname === "/api/v1/history/calendar") return respond({ month: url.searchParams.get("month"), entries: [], truncated: false });
    if (url.pathname === "/api/v1/history") {
      if (url.searchParams.has("cursor")) nextPageReads += 1;
      return respond({ histories: [{ id: 5, menuId: null, menuName: "국수", restaurantName: null,
        isVisited: true, recommendedAt: "2026-10-16T12:00:00", visitedAt: "2026-10-16T12:30:00",
        recommendationFeedback: null, filterConditions: [] }], nextCursor: 5, hasNext: true });
    }
    if (url.pathname === "/api/v1/history/export") {
      exports += 1;
      expect(url.searchParams.get("days")).toBe("30");
      expect(url.searchParams.get("visited")).toBe("true");
      expect(url.searchParams.get("keyword")).toBe("국수");
      expect(url.searchParams.has("cursor")).toBe(false);
      if (overLimit) return route.fulfill({ status: 400, contentType: "application/json", body: JSON.stringify({
        success: false, errorCode: "HISTORY_EXPORT_LIMIT_EXCEEDED", message: "내려받을 기록이 1,000건을 넘습니다. 기간이나 검색 조건을 좁혀주세요.",
      }) });
      return route.fulfill({ contentType: "text/csv;charset=UTF-8", body: csv,
        headers: { "Content-Disposition": 'attachment; filename="menupick-history-2026-10-16.csv"', "Cache-Control": "no-store" } });
    }
    throw new Error("처리하지 않은 요청: " + route.request().method() + " " + url.pathname);
  });
  await page.goto("/history");
  await page.getByRole("group", { name: "조회 기간" }).getByRole("button", { name: "30일", exact: true }).click();
  await page.getByRole("group", { name: "방문 상태" }).getByRole("button", { name: "방문", exact: true }).click();
  await page.getByRole("searchbox", { name: "픽 기록 검색" }).fill("국수");
  await page.getByRole("button", { name: "기록 검색", exact: true }).click();
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "CSV 내려받기", exact: true }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe("menupick-history.csv");
  const contents = await readFile((await download.path())!);
  expect([...contents.subarray(0, 3)]).toEqual([0xef, 0xbb, 0xbf]);
  expect(contents.toString("utf8")).toBe(csv);
  expect(nextPageReads).toBe(0);
  expect(exports).toBe(1);
  overLimit = true;
  await page.getByRole("button", { name: "CSV 내려받기", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("1,000건을 넘습니다");
  expect(downloads).toBe(1);
  expect(exports).toBe(2);
});
