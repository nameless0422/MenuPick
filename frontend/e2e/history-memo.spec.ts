import { expect, test } from "@playwright/test";

test("픽 기록 메모를 저장하고 수정 충돌에서 초안을 보존한 뒤 다시 저장하거나 지운다", async ({ page }) => {
  let memo: string | null = null;
  let version = 0;
  let memoReads = 0;
  let conflict = true;
  const sentVersions: number[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const respond = (data: unknown) => route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data }) });
    if (url.pathname === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (url.pathname === "/api/v1/history/summary") return respond({ periodDays: 30, picks: 0, eaten: 0, categories: [], menus: [], forgottenMenus: [] });
    if (url.pathname === "/api/v1/history/calendar") return respond({ month: url.searchParams.get("month"), entries: [], truncated: false });
    if (url.pathname === "/api/v1/history") return respond({ histories: [
      { id: 42, menuId: null, menuName: "김치찌개", restaurantName: "식당", isVisited: true,
        recommendedAt: "2026-10-09T12:00:00", visitedAt: "2026-10-09T12:30:00",
        recommendationFeedback: "ACCEPTED", filterConditions: [], memo },
    ], nextCursor: null, hasNext: false });
    if (url.pathname === "/api/v1/history/42/memo" && request.method() === "GET") {
      memoReads += 1;
      return respond({ historyId: 42, memo, version });
    }
    if (url.pathname === "/api/v1/history/42/memo" && request.method() === "PUT") {
      const body = request.postDataJSON();
      sentVersions.push(body.version);
      expect(body.version).toBe(version);
      if (version === 1 && conflict) {
        conflict = false;
        memo = "다른 탭의 메모";
        version += 1;
        return route.fulfill({ status: 409, contentType: "application/json",
          body: JSON.stringify({ success: false, errorCode: "CONCURRENT_MODIFICATION", message: "다른 곳에서 먼저 수정했습니다." }) });
      }
      memo = body.memo.trim() || null;
      version += 1;
      return respond({ historyId: 42, memo, version });
    }
    throw new Error("처리하지 않은 E2E 요청: " + request.method() + " " + url.pathname);
  });
  await page.goto("/history");
  await expect(page.getByText("김치찌개", { exact: true })).toBeVisible();
  expect(memoReads).toBe(0);
  await page.getByRole("button", { name: /김치찌개.*메모 쓰기/ }).click();
  const input = page.getByRole("textbox", { name: "픽 기록 메모" });
  await expect(input).toBeFocused();
  await input.fill("덜 맵게\n주문");
  await page.getByRole("button", { name: "메모 저장", exact: true }).click();
  await expect(input).toHaveCount(0);
  await expect(page.locator(".history-memo-text")).toHaveText("덜 맵게\n주문");
  await expect(page.getByRole("button", { name: /김치찌개.*메모 수정/ })).toBeFocused();
  await page.getByRole("button", { name: /김치찌개.*메모 수정/ }).click();
  await input.fill("내 수정안");
  await page.getByRole("button", { name: "메모 저장", exact: true }).click();
  await expect(page.getByText(/작성한 내용은 유지했습니다/)).toBeVisible();
  await expect(input).toHaveValue("내 수정안");
  await expect(page.getByRole("button", { name: "메모 저장", exact: true })).toHaveAttribute("aria-disabled", "true");
  await page.getByRole("button", { name: "최신 메모 확인", exact: true }).click();
  await expect(page.getByText("다른 탭의 메모", { exact: true })).toBeVisible();
  await expect(input).toHaveValue("내 수정안");
  await page.getByRole("button", { name: "메모 저장", exact: true }).click();
  await expect(input).toHaveCount(0);
  await expect(page.locator(".history-memo-text")).toHaveText("내 수정안");
  await page.getByRole("button", { name: /김치찌개.*메모 수정/ }).click();
  await input.fill("");
  await page.getByRole("button", { name: "메모 저장", exact: true }).click();
  await expect(input).toHaveCount(0);
  await expect(page.locator(".history-memo-text")).toHaveCount(0);
  await expect(page.getByRole("button", { name: /김치찌개.*메모 쓰기/ })).toBeFocused();
  expect(sentVersions).toEqual([0, 1, 2, 3]);
  await expect(page.getByText("방문완료", { exact: true })).toBeVisible();
});
