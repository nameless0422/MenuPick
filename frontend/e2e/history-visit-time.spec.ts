import { expect, test } from "@playwright/test";

test("방문 일시를 다른 달로 옮겨 달력·목록을 갱신하고 충돌 초안을 보존한다", async ({ page }) => {
  await page.clock.setFixedTime(new Date("2026-10-20T03:00:00Z"));
  let visitedAt = "2026-10-01T12:00:00";
  let version = 2;
  let writes = 0;
  let conflict = false;
  let octoberReads = 0;
  let summaryReads = 0;
  await page.route("**/api/v1/**", async route => {
    const request = route.request(); const url = new URL(request.url());
    const respond = (data: unknown) => route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data }) });
    if (url.pathname === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (url.pathname === "/api/v1/history/summary") {
      summaryReads += 1;
      return respond({ periodDays: 30, picks: 1, eaten: 1, categories: [], menus: [], forgottenMenus: [] });
    }
    if (url.pathname === "/api/v1/history/calendar") {
      const month = url.searchParams.get("month")!;
      if (month === "2026-10") octoberReads += 1;
      return respond({ month, entries: visitedAt.startsWith(month)
        ? [{ id: 42, menuName: "국수", restaurantName: null, visitedAt }] : [], truncated: false });
    }
    if (url.pathname === "/api/v1/history") return respond({ histories: url.searchParams.get("days") === "3650" ? [{
      id: 42, menuId: null, menuName: "국수", restaurantName: null, isVisited: true, visitedAt,
      recommendedAt: "2026-09-30T14:00:00", recommendationFeedback: "ACCEPTED", filterConditions: [], memo: "보존할 메모",
    }] : [], hasNext: false, nextCursor: null });
    if (url.pathname === "/api/v1/history/42/visit-time") {
      if (request.method() === "PUT") {
        writes += 1;
        const body = request.postDataJSON(); expect(body.version).toBe(version);
        if (conflict) return route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({
          success: false, errorCode: "CONCURRENT_MODIFICATION", message: "다른 곳에서 먼저 수정했습니다.",
        }) });
        expect(body.visitedAt).toBe("2026-09-30T18:30:00");
        visitedAt = body.visitedAt; version += 1;
      }
      return respond({ historyId: 42, visitedAt, version, earliestVisitAt: "2026-09-30T00:00:00", latestVisitAt: "2026-10-20T12:00:00" });
    }
    throw new Error("예상하지 않은 요청: " + request.method() + " " + url.pathname);
  });
  await page.goto("/history");
  await page.getByRole("group", { name: "조회 기간" }).getByRole("button", { name: "전체", exact: true }).click();
  const opener = page.getByRole("button", { name: /국수.*방문 일시 수정/ });
  await opener.click();
  const input = page.getByLabel("방문한 날짜와 시각 (한국 시간)");
  await expect(input).toBeFocused(); await input.fill("2026-09-30T18:30");
  await page.getByRole("button", { name: "방문 일시 저장", exact: true }).click();
  await expect(page.getByText("방문 일시를 수정했습니다.")).toBeVisible();
  await expect(opener).toBeFocused();
  await expect(page.locator(".history-visited-at time")).toHaveAttribute("datetime", visitedAt);
  await expect.poll(() => octoberReads).toBeGreaterThan(1);
  await expect.poll(() => summaryReads).toBeGreaterThan(1);
  await page.getByRole("button", { name: "이전 달", exact: true }).click();
  await expect(page.getByRole("cell", { name: "9월 30일, 방문 기록 1개" })).toContainText("국수");
  await expect(page.getByText("보존할 메모", { exact: true })).toBeVisible();
  conflict = true;
  await opener.click(); await input.fill("2026-10-01T10:00");
  await page.getByRole("button", { name: "방문 일시 저장", exact: true }).click();
  await expect(page.getByText(/입력한 일시는 유지했습니다/)).toBeVisible();
  await expect(input).toHaveValue("2026-10-01T10:00");
  await page.getByRole("button", { name: "방문 일시 저장", exact: true }).click({ force: true });
  expect(writes).toBe(2);
  await page.getByRole("button", { name: "방문 일시 편집 취소" }).click();
  await expect(opener).toBeFocused();
});
