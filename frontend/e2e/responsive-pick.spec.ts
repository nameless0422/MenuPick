import { expect, test, type Page } from "@playwright/test";

async function installApi(page: Page) {
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const data: Record<string, unknown> = {
      "/api/v1/auth/refresh": { accessToken: "layout-test-token" },
      "/api/v1/tags": [],
      "/api/v1/pick/preferences": [],
      "/api/v1/pick/presets": { presets: [], limit: 10 },
      "/api/v1/menus": { menus: [], hasNext: false, nextCursor: null },
      "/api/v1/onboarding": { needed: false, menus: [] },
      "/api/v1/history": { histories: [], hasNext: false, nextCursor: null },
      "/api/v1/trends": { status: "DISABLED", categories: [], menus: [], windowDays: 7 },
      "/api/v1/pick": {
        historyId: 1,
        menu: { id: 1, name: "김치찌개", weight: 1, categories: ["한식"], tags: [], isExcluded: false },
        restaurants: [], reasons: [],
      },
    };
    if (!(path in data)) throw new Error(`처리하지 않은 API 요청: ${path}`);
    await route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data: data[path] }) });
  });
}

for (const route of ["/login", "/menus"]) {
  test(`${route}은 모바일에서 기본 글자 크기를 200%로 키워도 입력창이 잘리지 않는다`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await installApi(page);
    await page.goto(route);
    await expect(page.getByLabel(route === "/login" ? "이메일" : "내 메뉴 검색", { exact: true })).toBeVisible();
    await page.addStyleTag({ content: ":root { font-size: 200%; }" });
    await page.evaluate(() => document.fonts.ready);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    for (const input of await page.locator("input:visible").all()) {
      const box = await input.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(390);
    }
  });
}

for (const viewport of [{ width: 320, height: 740 }, { width: 390, height: 844 },
  { width: 820, height: 1180 }, { width: 1440, height: 1000 }]) {
  test(`${viewport.width}px에서 뽑기와 결과를 스크롤 없이 확인하고 탐색할 수 있다`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.emulateMedia({ reducedMotion: "reduce" });
    await installApi(page);
    await page.goto("/pick");
    const pick = page.getByRole("button", { name: "오늘의 메뉴 뽑기" });
    await expect(pick).toBeInViewport({ ratio: 1 });
    // 고정 탐색 메뉴에 가려지는지도 본다. toBeVisible만으로는 화면 가림을 잡지 못한다.
    await pick.click({ trial: true });
    await pick.click();
    await expect(page.getByText("김치찌개", { exact: true })).toBeInViewport({ ratio: 1 });
    await expect(page.getByRole("link", { name: viewport.width <= 760 ? "오늘" : "오늘 뭐 먹지", exact: true })).toHaveAttribute("aria-current", "page");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (viewport.width <= 760) {
      await page.getByRole("button", { name: /조건 설정/ }).click();
      await page.getByRole("dialog").getByRole("button", { name: "한식", exact: true }).click();
      await page.getByRole("button", { name: "조건 적용" }).click();
    } else {
      await page.getByRole("button", { name: "한식", exact: true }).click();
      await expect(page.getByRole("button", { name: "한식", exact: true })).toHaveAttribute("aria-pressed", "true");
    }
    // 긴 조건 목록의 끝에서도 탐색 링크와 로그아웃 경로가 사라지지 않는다.
    if (viewport.width <= 760) {
      await expect(page.getByRole("navigation").getByRole("link")).toHaveCount(4);
      await page.getByRole("button", { name: "계정 메뉴" }).click();
      await page.getByRole("link", { name: "설정 열기", exact: true }).click({ trial: true });
    } else {
      await page.getByRole("navigation", { name: "주요 메뉴" }).getByRole("link", { name: "설정", exact: true }).click({ trial: true });
    }
    await page.getByRole("button", { name: "로그아웃" }).click({ trial: true });
  });
}
