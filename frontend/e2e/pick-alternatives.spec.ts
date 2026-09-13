import { expect, test, type Page, type Route } from "@playwright/test";

function response(route: Route, data: unknown, status = 200, errorCode?: string) {
  return route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(errorCode
      ? { success: false, message: "조건에 맞는 메뉴가 없습니다.", errorCode }
      : { success: true, data }),
  });
}

async function installApi(page: Page, alternatives: unknown[]) {
  let picks = 0;
  let alternativeCalls = 0;
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    if (path === "/api/v1/auth/refresh") return response(route, { accessToken: "e2e-token" });
    if (path === "/api/v1/tags") return response(route, []);
    if (path === "/api/v1/pick/preferences") return response(route, []);
    if (path === "/api/v1/pick/presets") return response(route, { presets: [], limit: 10 });
    if (path === "/api/v1/trends") return response(route, {
      status: "DISABLED", categories: [], menus: [], windowDays: 7, minUsers: 5, maxLabels: 10,
    });
    if (path === "/api/v1/pick/alternatives" && method === "POST") {
      alternativeCalls += 1;
      return response(route, { alternatives });
    }
    if (path === "/api/v1/pick" && method === "POST") {
      picks += 1;
      if (picks === 1) return response(route, null, 404, "NO_PICK_CANDIDATES");
      return response(route, {
        historyId: 9,
        menu: {
          id: 2, name: "비빔밥", memo: null, weight: 1, isExcluded: false,
          categories: ["한식"], tags: [], createdAt: "2026-09-14T00:00:00",
          updatedAt: "2026-09-14T00:00:00", version: 0,
        },
        restaurants: [], reasons: ["최근 추천되지 않은 메뉴예요"],
      });
    }
    throw new Error(`처리하지 않은 E2E API 요청: ${method} ${path}`);
  });
  return { picks: () => picks, alternativeCalls: () => alternativeCalls };
}

test("후보 없음에서 명시한 조건만 적용해 기존 픽으로 성공한다", async ({ page }) => {
  const state = await installApi(page, [
    { type: "CLEAR_CATEGORIES", candidateCount: 3, changes: { categories: [] } },
  ]);
  await page.goto("/pick");
  await page.getByRole("button", { name: "한식" }).click();
  await page.getByRole("button", { name: "오늘의 메뉴 뽑기" }).click();
  const alternative = page.getByRole("button", { name: /카테고리 조건을 모두 풀고 다시 뽑기 · 후보 3개/ });
  await expect(alternative).toBeVisible();
  await alternative.click();
  await expect(page.getByText("비빔밥", { exact: true })).toBeVisible();
  await expect.poll(state.picks).toBe(2);
  await expect.poll(state.alternativeCalls).toBe(1);
  await expect(page.getByRole("button", { name: "한식" })).toHaveAttribute("aria-pressed", "false");
});

test("빈 대안은 기존 안내와 CTA를 가리지 않는다", async ({ page }) => {
  const state = await installApi(page, []);
  await page.goto("/pick");
  await page.getByRole("button", { name: "오늘의 메뉴 뽑기" }).click();
  await expect(page.getByText(/조건에 맞는 메뉴가 없어요/)).toBeVisible();
  await expect(page.getByRole("link", { name: /내 메뉴 관리하러 가기/ })).toBeVisible();
  await expect(page.getByRole("heading", { name: "조건을 이렇게 바꿔볼까요?" })).toHaveCount(0);
  await expect.poll(state.alternativeCalls).toBe(1);
});
