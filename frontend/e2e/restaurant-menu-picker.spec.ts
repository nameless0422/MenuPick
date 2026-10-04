import { expect, test } from "@playwright/test";

test("식당 연결 폼에서 예전 메뉴를 검색하고 다음 페이지에서 연결한다", async ({ page }) => {
  const menu = { id: 100, name: "김치찌개", weight: 3, isExcluded: false, categories: [], tags: [] };
  const olderMenu = { ...menu, id: 5, name: "된장찌개" };
  const restaurant = {
    id: 1, name: "테스트식당", address: "서울 중구", latitude: 37.5665, longitude: 126.978,
    phone: null, naverUrl: null, version: 0,
  };
  const searches: { keyword: string | null; cursor: string | null }[] = [];
  let links = 0;
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const respond = (data: unknown) => route.fulfill({
      contentType: "application/json", body: JSON.stringify({ success: true, data }),
    });
    if (path === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (path === "/api/v1/restaurants") return respond([restaurant]);
    if (path === "/api/v1/restaurants/1") return respond(restaurant);
    if (path === "/api/v1/menus") {
      const keyword = url.searchParams.get("keyword");
      const cursor = url.searchParams.get("cursor");
      searches.push({ keyword, cursor });
      if (!keyword) return respond({ menus: [menu], nextCursor: null, hasNext: false });
      return respond(cursor
        ? { menus: [olderMenu], nextCursor: null, hasNext: false }
        : { menus: [menu], nextCursor: 100, hasNext: true });
    }
    if (path === "/api/v1/menus/5/restaurants" && request.method() === "POST") {
      expect(request.postDataJSON()).toEqual({ restaurantId: 1, rating: 3, memo: null });
      links += 1;
      return respond({ menuId: 5, restaurantId: 1, rating: 3, memo: null });
    }
    throw new Error(`처리하지 않은 E2E API 요청: ${request.method()} ${path}`);
  });

  await page.goto("/restaurants");
  await page.getByRole("button", { name: "테스트식당 메뉴 연결" }).click();
  await page.getByRole("button", { name: "김치찌개", exact: true }).click();
  const search = page.getByRole("textbox", { name: "연결할 메뉴 검색" });
  await search.fill("찌개");
  await search.press("Enter");
  await expect(page.getByRole("button", { name: "연결", exact: true })).toHaveAttribute("aria-disabled", "true");
  expect(links).toBe(0);
  await page.getByRole("button", { name: "메뉴 더 보기" }).click();
  await page.getByRole("button", { name: "된장찌개", exact: true }).click();
  expect(searches).toContainEqual({ keyword: "찌개", cursor: "100" });
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect.poll(() => links).toBe(1);
  await expect(page.getByRole("heading", { name: "메뉴 연결" })).toHaveCount(0);
});
