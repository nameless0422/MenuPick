import { expect, test } from "@playwright/test";

test("식당 20개의 목록은 상세 요청 없이 표시하고 수정할 때만 최신 정보를 읽는다", async ({ page }) => {
  const restaurants = Array.from({ length: 20 }, (_, index) => ({
    id: index + 1, name: "저장한 식당 " + (index + 1), address: "서울 중구",
    phone: "02-" + (1000 + index), latitude: 37.5, longitude: 127,
    naverUrl: "https://place.map.kakao.com/" + (index + 1),
  }));
  let listRequests = 0;
  const detailRequests: number[] = [];
  let version = 4;
  let name = restaurants[0].name;
  let phone = "02-9999";
  const detail = () => ({
    ...restaurants[0], name, phone, version, kakaoPlaceId: "1",
    createdAt: "2026-10-01T12:00:00", updatedAt: "2026-10-15T12:00:00",
  });
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const respond = (data: unknown) => route.fulfill({
      contentType: "application/json", body: JSON.stringify({ success: true, data }),
    });
    if (url.pathname === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (url.pathname === "/api/v1/restaurants") { listRequests += 1; return respond(restaurants); }
    const match = url.pathname.match(/^\/api\/v1\/restaurants\/(\d+)$/);
    if (match && request.method() === "GET") {
      detailRequests.push(Number(match[1]));
      return respond(detail());
    }
    if (url.pathname === "/api/v1/restaurants/1" && request.method() === "PUT") {
      expect(request.postDataJSON().version).toBe(7);
      name = request.postDataJSON().name;
      phone = request.postDataJSON().phone;
      version += 1;
      restaurants[0] = { ...restaurants[0], name, phone };
      return respond(detail());
    }
    throw new Error("처리하지 않은 API 요청: " + request.method() + " " + url.pathname);
  });
  await page.goto("/restaurants");
  await expect(page.getByRole("button", { name: /^저장한 식당 \d+ 수정$/ })).toHaveCount(20);
  await expect(page.getByText("02-1000", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "저장한 식당 20 지도 보기 (새 창)" }))
    .toHaveAttribute("href", "https://place.map.kakao.com/20");
  expect(listRequests).toBe(1);
  expect(detailRequests).toEqual([]);

  await page.getByRole("button", { name: "저장한 식당 1 수정", exact: true }).click();
  await expect(page.getByLabel("전화", { exact: true })).toHaveValue("02-9999");
  expect(detailRequests).toEqual([1]);
  await page.getByRole("button", { name: "취소", exact: true }).click();
  await expect(page.getByRole("button", { name: "저장한 식당 1 수정", exact: true })).toBeFocused();
  version = 7;
  phone = "02-7777";
  await page.getByRole("button", { name: "저장한 식당 1 수정", exact: true }).click();
  await expect(page.getByLabel("전화", { exact: true })).toHaveValue("02-7777");
  await page.getByLabel("식당 이름", { exact: true }).fill("수정한 식당");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(page.getByRole("button", { name: "수정한 식당 수정", exact: true })).toBeVisible();
  await expect(page.getByText("02-7777", { exact: true })).toBeVisible();
  expect(detailRequests).toEqual([1, 1]);
});
