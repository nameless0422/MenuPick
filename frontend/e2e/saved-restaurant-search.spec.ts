import { expect, test } from "@playwright/test";

test("저장한 식당을 주소로 찾고 수정 후 검색 결과와 전체 목록이 갱신된다", async ({ page }) => {
  let restaurant = {
    id: 1, name: "진주회관", address: "서울 중구", latitude: 37.5665, longitude: 126.978,
    phone: null, naverUrl: null, kakaoPlaceId: null, version: 0,
    createdAt: "2026-10-06T12:00:00", updatedAt: "2026-10-06T12:00:00",
  };
  const other = { ...restaurant, id: 2, name: "강남식당", address: "서울 강남구" };
  const keywords: (string | null)[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const respond = (data: unknown) => route.fulfill({
      contentType: "application/json", body: JSON.stringify({ success: true, data }),
    });
    if (url.pathname === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (url.pathname === "/api/v1/restaurants") {
      const keyword = url.searchParams.get("keyword");
      keywords.push(keyword);
      return respond([restaurant, other].filter((row) => !keyword || row.name.includes(keyword) || row.address.includes(keyword)));
    }
    if (url.pathname === "/api/v1/restaurants/1") {
      if (request.method() === "PUT") {
        expect(request.postDataJSON().version).toBe(0);
        restaurant = { ...restaurant, ...request.postDataJSON(), version: 1 };
      }
      return respond(restaurant);
    }
    if (url.pathname === "/api/v1/restaurants/2") return respond(other);
    throw new Error(`처리하지 않은 E2E API 요청: ${request.method()} ${url.pathname}`);
  });

  await page.goto("/restaurants");
  await expect(page.getByText("강남식당", { exact: true })).toBeVisible();
  const search = page.getByRole("searchbox", { name: "저장한 식당 검색" });
  await search.fill("  중구  ");
  await search.press("Enter");
  await expect(page.getByText("‘중구’ 검색 결과 1곳")).toBeVisible();
  expect(keywords).toContain("중구");
  await expect(page.getByText("강남식당", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "진주회관 수정" }).click();
  await page.getByLabel("주소", { exact: true }).fill("서울 종로구");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(page.getByText(/‘중구’ 검색 결과가 없습니다/)).toBeVisible();
  await page.getByRole("button", { name: "검색 초기화" }).click();
  await expect(page.getByText("서울 종로구")).toBeVisible();
  await expect(page.getByText("강남식당", { exact: true })).toBeVisible();
  await expect(search).toHaveValue("");
  await expect(search).toBeFocused();
});
