import { expect, test } from "@playwright/test";

test("메뉴를 삭제하고 휴지통에서 같은 메뉴를 복원한다", async ({ page }) => {
  const menu = { id: 10, name: "김치찌개", weight: 4, isExcluded: true, categories: ["한식"], tags: [], pausedUntil: null };
  let deleted = false;
  let trashRequests = 0;
  let restores = 0;
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const respond = (data: unknown) => route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data }) });
    if (url.pathname === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (url.pathname === "/api/v1/menus") return respond({ menus: deleted ? [] : [menu], nextCursor: null, hasNext: false });
    if (url.pathname === "/api/v1/menus/10" && request.method() === "DELETE") { deleted = true; return respond(null); }
    if (url.pathname === "/api/v1/menus/trash") {
      trashRequests += 1;
      return respond({ menus: deleted ? [{ id: 10, name: menu.name, deletedAt: "2026-10-07T00:15:00", version: 1 }] : [], nextCursor: null, hasNext: false });
    }
    if (url.pathname === "/api/v1/menus/10/restore") {
      expect(request.postDataJSON()).toEqual({ version: 1 });
      restores += 1;
      deleted = false;
      return respond(null);
    }
    throw new Error(`처리하지 않은 E2E 요청: ${request.method()} ${url.pathname}`);
  });

  await page.goto("/menus");
  await expect(page.getByText("김치찌개", { exact: true })).toBeVisible();
  expect(trashRequests).toBe(0);
  page.on("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "김치찌개 삭제" }).click();
  await expect(page.getByText(/등록된 메뉴가 없습니다/)).toBeVisible();
  await page.getByRole("button", { name: "휴지통 보기" }).click();
  const trash = page.getByRole("region", { name: "메뉴 휴지통" });
  await expect(trash.getByText("삭제일: 2026-10-07")).toBeVisible();
  await trash.getByRole("button", { name: "김치찌개 복원" }).click();
  await expect(trash.getByText("삭제한 메뉴가 없습니다.")).toBeVisible();
  await expect(trash.getByRole("button", { name: "휴지통 닫기" })).toBeFocused();
  await expect(page.getByRole("button", { name: "김치찌개 수정" })).toBeVisible();
  await expect(page.getByText("추천 제외", { exact: true })).toBeVisible();
  expect(restores).toBe(1);
});
