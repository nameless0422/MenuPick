import { expect, test } from "@playwright/test";

test("같은 이름의 예전 메뉴도 이력 ID에 맞는 식당으로 방문 처리한다", async ({ page }) => {
  const visits: { historyId: number; restaurantId: number }[] = [];
  const candidatesRequested: number[] = [];
  const histories = [7, 8].map((menuId, i) => ({
    id: 10 + i, menuId, menuName: "김치찌개", restaurantName: null, isVisited: false,
    recommendedAt: `2026-10-05T12:0${i}:00`, visitedAt: null,
    recommendationFeedback: null, filterConditions: [],
  }));
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const respond = (data: unknown) => route.fulfill({
      contentType: "application/json", body: JSON.stringify({ success: true, data }),
    });
    if (path === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (path === "/api/v1/history") return respond({ histories, nextCursor: null, hasNext: false });
    if (path === "/api/v1/history/summary") return respond({
      periodDays: 30, picks: 2, eaten: visits.length, categories: [], menus: [], forgottenMenus: [],
    });
    if (path === "/api/v1/history/calendar") return respond({
      month: url.searchParams.get("month"), entries: [], truncated: false,
    });
    const menuPath = path.match(/^\/api\/v1\/menus\/(7|8)\/restaurants$/);
    if (menuPath) {
      const menuId = Number(menuPath[1]);
      candidatesRequested.push(menuId);
      return respond({ menuRestaurants: [1, 2].map((i) => ({
        menuId, restaurantId: (menuId - 5) * 10 + i,
        restaurantName: `${menuId}번 메뉴의 식당 ${i}`, rating: 3, memo: null,
      })) });
    }
    const visitPath = path.match(/^\/api\/v1\/history\/(10|11)\/visit$/);
    if (visitPath && request.method() === "PATCH") {
      const historyId = Number(visitPath[1]);
      visits.push({ historyId, restaurantId: request.postDataJSON().restaurantId });
      histories.find((h) => h.id === historyId)!.isVisited = true;
      return respond(null);
    }
    // 메뉴 목록 API는 허용하지 않는다. 예전 메뉴의 식당 선택도 목록 조회 없이 가능해야 한다.
    throw new Error(`처리하지 않은 E2E API 요청: ${request.method()} ${path}`);
  });

  await page.goto("/history");
  const rows = page.getByRole("listitem");
  await expect(rows).toHaveCount(2);
  expect(candidatesRequested).toEqual([]);
  await rows.nth(0).getByRole("button", { name: "김치찌개 방문했어요" }).click();
  await rows.nth(0).getByRole("combobox", { name: "김치찌개 방문 식당 선택" }).selectOption("22");
  await rows.nth(0).getByRole("button", { name: "김치찌개 선택한 식당으로 방문 확정" }).click();
  await expect.poll(() => visits).toEqual([{ historyId: 10, restaurantId: 22 }]);
  await rows.nth(1).getByRole("button", { name: "김치찌개 방문했어요" }).click();
  await rows.nth(1).getByRole("combobox", { name: "김치찌개 방문 식당 선택" }).selectOption("31");
  await rows.nth(1).getByRole("button", { name: "김치찌개 선택한 식당으로 방문 확정" }).click();
  await expect.poll(() => visits).toEqual([
    { historyId: 10, restaurantId: 22 }, { historyId: 11, restaurantId: 31 },
  ]);
  expect(candidatesRequested).toEqual([7, 8]);
});
