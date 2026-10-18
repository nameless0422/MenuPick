import { expect, test } from "@playwright/test";

test("검토가 필요한 빠른 픽을 수정·복구하고 충돌 초안을 유지한 뒤 취소한다", async ({ page }) => {
  let preset = { id: 1, name: "회사 점심", categories: ["한식"], includeTagIds: [7],
    additionalExcludeTagIds: [] as number[], maxDistance: null as number | null,
    version: 4, needsReview: true, createdAt: "2026-10-19T12:00:00", updatedAt: "2026-10-19T12:00:00" };
  let writes = 0;
  let conflict = false;
  await page.route("**/api/v1/**", async route => {
    const path = new URL(route.request().url()).pathname;
    const method = route.request().method();
    const respond = (data: unknown) => route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data }) });
    if (path === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (path === "/api/v1/pick/presets") return respond({ presets: [preset], limit: 10 });
    if (path === "/api/v1/pick/presets/1" && method === "GET") return respond(preset);
    if (path === "/api/v1/pick/presets/1" && method === "PUT") {
      writes += 1;
      const body = route.request().postDataJSON();
      expect(body.version).toBe(preset.version);
      if (conflict) return route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({
        success: false, errorCode: "CONCURRENT_MODIFICATION", message: "다른 곳에서 먼저 수정했습니다.",
      }) });
      expect(body.acknowledgeRemovedTags).toBe(true);
      expect(body.categories).toEqual(["중식", "직접 분류"]);
      expect(body.includeTagIds).toEqual([]);
      expect(body.additionalExcludeTagIds).toEqual([7]);
      expect(body.maxDistance).toBe(1000);
      preset = { ...preset, ...body, needsReview: false, version: preset.version + 1 };
      return respond(preset);
    }
    if (path === "/api/v1/tags") return respond([{ id: 7, name: "혼밥", version: 0, createdAt: "" }]);
    if (path === "/api/v1/pick/preferences") return respond([]);
    if (path === "/api/v1/onboarding") return respond({ needed: false, menus: [] });
    if (path === "/api/v1/history") return respond({ histories: [], hasNext: false, nextCursor: null });
    if (path === "/api/v1/trends") return respond({ status: "DISABLED", categories: [], menus: [], windowDays: 7, minUsers: 5, maxLabels: 10 });
    throw new Error("예상하지 않은 요청: " + method + " " + path);
  });
  await page.goto("/pick");
  await page.getByRole("button", { name: /회사 점심.*확인 필요/ }).click();
  await page.getByRole("button", { name: "회사 점심 조건 수정" }).click();
  await expect(page.getByRole("heading", { name: "빠른 픽 조건 수정" })).toBeFocused();
  await page.getByLabel("수정할 빠른 픽 이름").fill("주말 점심");
  await page.getByLabel("수정할 카테고리").fill("중식\n직접 분류");
  await page.getByLabel("수정할 거리 조건").selectOption("1000");
  const form = page.getByRole("form", { name: "빠른 픽 조건 수정" });
  await form.getByLabel("혼밥", { exact: true }).selectOption("exclude");
  await page.getByRole("button", { name: "수정 저장", exact: true }).click({ force: true });
  expect(writes).toBe(0);
  await page.getByRole("checkbox", { name: /남은 전체 조건을 확인/ }).check();
  await page.getByRole("button", { name: "수정 저장", exact: true }).click();
  await expect(page.getByRole("button", { name: "주말 점심 조건 수정" })).toBeFocused();
  await expect(page.getByRole("button", { name: "이 조건으로 뽑기" })).not.toHaveAttribute("aria-disabled", "true");
  conflict = true;
  await page.getByRole("button", { name: "주말 점심 조건 수정" }).click();
  await page.getByLabel("수정할 빠른 픽 이름").fill("충돌 후 초안");
  await page.getByRole("button", { name: "수정 저장", exact: true }).click();
  await expect(page.getByText(/입력 내용은 유지했습니다/)).toBeVisible();
  await expect(page.getByLabel("수정할 빠른 픽 이름")).toHaveValue("충돌 후 초안");
  await page.getByRole("button", { name: "수정 저장", exact: true }).click({ force: true });
  expect(writes).toBe(2);
  await page.getByRole("button", { name: "수정 취소" }).click();
  await expect(page.getByRole("button", { name: "주말 점심 조건 수정" })).toBeFocused();
});
