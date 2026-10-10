import { expect, test } from "@playwright/test";

test("식당을 삭제하고 정보 보존 안내를 확인한 뒤 휴지통에서 복원한다", async ({ page }) => {
  const restaurant = {
    id: 10, name: "직접 수정한 지점명", address: "서울 중구 수정한 주소", phone: "02-1234",
    latitude: 37.5, longitude: 127.0, naverUrl: "https://place.map.kakao.com/123", kakaoPlaceId: "123",
    createdAt: "2026-10-01T12:00:00", updatedAt: "2026-10-10T12:00:00", version: 0,
  };
  let deleted = false;
  let trashRequests = 0;
  let restores = 0;
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const respond = (data: unknown) => route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data }) });
    if (url.pathname === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (url.pathname === "/api/v1/restaurants") return respond(deleted ? [] : [restaurant]);
    if (url.pathname === "/api/v1/restaurants/10") {
      if (request.method() === "DELETE") { deleted = true; restaurant.version += 1; return respond(null); }
      return respond(restaurant);
    }
    if (url.pathname === "/api/v1/restaurants/trash") {
      trashRequests += 1;
      expect(url.searchParams.get("size")).toBe("20");
      return respond({ restaurants: deleted ? [{ ...restaurant, deletedAt: "2026-10-10T00:15:00" }] : [], nextCursor: null, hasNext: false });
    }
    if (url.pathname === "/api/v1/restaurants/10/restore") {
      expect(request.postDataJSON()).toEqual({ version: 1 });
      restores += 1;
      deleted = false;
      restaurant.version += 1;
      return respond(null);
    }
    throw new Error(`처리하지 않은 E2E 요청: ${request.method()} ${url.pathname}`);
  });

  await page.goto("/restaurants");
  await expect(page.getByRole("button", { name: "직접 수정한 지점명 수정" })).toBeEnabled();
  expect(trashRequests).toBe(0);
  const trash = page.getByRole("region", { name: "식당 휴지통" });
  await trash.getByRole("button", { name: "휴지통 보기" }).click();
  await expect(trash.getByText("삭제한 식당이 없습니다.")).toBeVisible();
  page.on("dialog", async (dialog) => {
    expect(dialog.message()).toContain("메뉴 연결과 연결별 별점·메모는 삭제됩니다.");
    await dialog.accept();
  });
  await page.getByRole("button", { name: "직접 수정한 지점명 삭제" }).click();
  await expect(page.getByText(/저장한 식당이 없습니다/)).toBeVisible();
  await expect(trash.getByText("삭제일: 2026-10-10")).toBeVisible();
  await expect(trash.getByText("서울 중구 수정한 주소", { exact: true })).toBeVisible();
  await expect(trash.getByText(/메뉴 연결과 연결별 별점·메모는 복원되지 않습니다/)).toBeVisible();
  await trash.getByRole("button", { name: "직접 수정한 지점명 복원" }).click();
  await expect(trash.getByText("삭제한 식당이 없습니다.")).toBeVisible();
  await expect(trash.getByRole("button", { name: "휴지통 닫기" })).toBeFocused();
  await expect(page.getByRole("button", { name: "직접 수정한 지점명 수정" })).toBeEnabled();
  await expect(page.getByText("서울 중구 수정한 주소", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "직접 수정한 지점명 지도 보기 (새 창)" })).toHaveAttribute("href", restaurant.naverUrl);
  await page.getByRole("button", { name: "직접 수정한 지점명 수정" }).click();
  await expect(page.getByLabel("전화")).toHaveValue("02-1234");
  await expect(page.getByLabel("주소", { exact: true })).toHaveValue(restaurant.address);
  expect(restores).toBe(1);
});
