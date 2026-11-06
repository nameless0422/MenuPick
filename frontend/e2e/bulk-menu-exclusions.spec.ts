import { expect, test, type Page } from "@playwright/test";
import type { MenuSummary } from "../src/api/menus";

async function installApi(page: Page, options: { gate?: Promise<void>; failOnce?: boolean; longNames?: boolean } = {}) {
  const menus: MenuSummary[] = [
    { id: 30, name: options.longNames ? "아주 긴 이름으로 등록한 김치찌개 메뉴" : "김치찌개", weight: 3,
      isExcluded: false, categories: ["한식"], tags: [], pausedUntil: "2026-12-01T00:00:00" },
    { id: 28, name: "비빔밥", weight: 4, isExcluded: true, categories: ["한식"], tags: [] },
    { id: 20, name: "김치볶음밥", weight: 2, isExcluded: false, categories: ["한식"], tags: [] },
  ];
  const writes: { entries: { menuId: number; excluded: boolean }[] }[] = [];
  const reads: URL[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const respond = (data: unknown) => route.fulfill({ contentType: "application/json",
      body: JSON.stringify({ success: true, data }) });
    if (url.pathname === "/api/v1/auth/refresh") return respond({ accessToken: "bulk-menu-test-token" });
    if (url.pathname === "/api/v1/menus" && request.method() === "GET") {
      reads.push(url);
      const cursor = Number(url.searchParams.get("cursor") || Number.MAX_SAFE_INTEGER);
      const filtered = menus.filter((menu) => menu.id < cursor
        && menu.name.includes(url.searchParams.get("keyword") || "")
        && (!url.searchParams.get("category") || menu.categories.includes(url.searchParams.get("category")!)));
      const items = filtered.slice(0, url.searchParams.has("keyword") ? 1 : 2);
      return respond({ menus: items, hasNext: filtered.length > items.length, nextCursor: items.at(-1)?.id ?? null });
    }
    if (url.pathname === "/api/v1/menus/exclusions" && request.method() === "PATCH") {
      const body = request.postDataJSON();
      writes.push(body);
      if (writes.length === 1) {
        await options.gate;
        if (options.failOnce) return route.fulfill({ status: 503, contentType: "application/json",
          body: JSON.stringify({ success: false, errorCode: "SERVICE_UNAVAILABLE", message: "다시 시도해 주세요." }) });
      }
      for (const entry of body.entries) menus.find((menu) => menu.id === entry.menuId)!.isExcluded = entry.excluded;
      return respond(null);
    }
    throw new Error(`처리하지 않은 API: ${request.method()} ${url.pathname}`);
  });
  return { menus, writes, reads };
}

test("검색 결과와 다음 페이지에서 고른 메뉴만 한 번에 제외·포함하고 검색 조건과 초점을 유지한다", async ({ page }) => {
  const state = await installApi(page);
  await page.goto("/menus");
  await page.getByRole("searchbox", { name: "내 메뉴 검색" }).fill("김치");
  await page.getByLabel("조회 카테고리").fill("한식");
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await expect(page.getByText("김치찌개", { exact: true })).toBeVisible();
  const start = page.getByRole("button", { name: "여러 메뉴 선택", exact: true });
  await start.click();
  const section = page.getByRole("region", { name: "여러 메뉴 추천 설정" });
  await expect(section.getByRole("heading")).toBeFocused();
  await expect(section.getByText(/적용한 조건: 이름 김치 · 카테고리 한식/)).toBeVisible();
  await section.getByRole("checkbox", { name: "김치찌개 선택" }).check();
  await section.getByRole("button", { name: "선택할 메뉴 더 보기" }).click();
  const second = section.getByRole("checkbox", { name: "김치볶음밥 선택" });
  await expect(second).not.toBeChecked();
  await expect(second).toBeEnabled();
  await second.check();
  await section.getByRole("button", { name: "선택한 메뉴 추천 제외" }).click();
  const result = section.getByRole("status", { name: "여러 메뉴 설정 결과" });
  await expect(result).toHaveText("2개 메뉴를 추천에서 제외했습니다.");
  await expect(result).toBeFocused();
  expect(state.writes).toEqual([{ entries: [{ menuId: 30, excluded: true }, { menuId: 20, excluded: true }] }]);
  expect(state.menus[0]).toMatchObject({ weight: 3, pausedUntil: "2026-12-01T00:00:00", categories: ["한식"] });
  expect(state.menus[1].isExcluded).toBe(true);
  await second.check();
  await section.getByRole("button", { name: "선택한 메뉴 추천 포함" }).click();
  await expect(result).toHaveText("1개 메뉴를 추천에 포함했습니다.");
  expect(state.writes[1]).toEqual({ entries: [{ menuId: 20, excluded: false }] });
  expect(state.menus[0].isExcluded).toBe(true);
  await section.getByRole("button", { name: "선택 종료" }).click();
  await expect(start).toBeFocused();
  await expect(page.getByRole("searchbox", { name: "내 메뉴 검색" })).toHaveValue("김치");
  await expect(page.getByLabel("조회 카테고리")).toHaveValue("한식");
});

test("모바일 저장 중 다른 작업과 중복 제출을 막고 오류 뒤 선택을 유지해 재시도한다", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  const state = await installApi(page, { gate, failOnce: true });
  await page.goto("/menus");
  await page.getByRole("button", { name: "여러 메뉴 선택", exact: true }).click();
  const section = page.getByRole("region", { name: "여러 메뉴 추천 설정" });
  await section.getByRole("checkbox", { name: "김치찌개 선택" }).check();
  await section.getByRole("button", { name: "선택한 메뉴 추천 제외" }).click();
  await expect.poll(() => state.writes.length).toBe(1);
  await expect(section.getByRole("checkbox", { name: "비빔밥 선택" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "+ 새 메뉴", exact: true })).toBeDisabled();
  await expect(page.getByRole("searchbox")).toHaveCount(0);
  const close = section.getByRole("button", { name: "선택 종료" });
  await close.focus();
  await page.keyboard.press("Enter");
  await expect(section).toBeVisible();
  const save = section.getByRole("button", { name: "선택한 메뉴 추천 포함" });
  await save.focus();
  await page.keyboard.press("Enter");
  expect(state.writes).toHaveLength(1);
  release();
  await expect(section.getByRole("alert")).toContainText("선택은 유지했습니다");
  const selected = section.getByRole("checkbox", { name: "김치찌개 선택" });
  await expect(selected).toBeChecked();
  await expect(selected).toBeEnabled();
  await section.getByRole("button", { name: "선택한 메뉴 추천 제외" }).click();
  await expect(section.getByRole("status", { name: "여러 메뉴 설정 결과" })).toHaveText("1개 메뉴를 추천에서 제외했습니다.");
  expect(state.writes).toHaveLength(2);
  expect(state.writes[1]).toEqual(state.writes[0]);
});

for (const width of [320, 1440]) {
  test(`${width}px에서 글자 200%로 확대해도 메뉴 선택과 적용 버튼을 조작한다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ colorScheme: width === 320 ? "dark" : "light" });
    await installApi(page, { longNames: true });
    await page.goto("/menus");
    await page.addStyleTag({ content: ":root { font-size: 200%; }" });
    await page.getByRole("button", { name: "여러 메뉴 선택", exact: true }).click();
    const section = page.getByRole("region", { name: "여러 메뉴 추천 설정" });
    await expect(section.getByRole("checkbox")).toHaveCount(2);
    await page.evaluate(() => document.fonts.ready);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    for (const control of await section.locator("label, button").all()) {
      const box = await control.boundingBox();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(width);
      if (width === 320) expect(box!.height).toBeGreaterThanOrEqual(44);
    }
    await section.getByRole("button", { name: "불러온 2개 선택" }).click();
    await section.getByRole("button", { name: "선택한 메뉴 추천 제외" }).click();
    await expect(section.getByRole("status", { name: "여러 메뉴 설정 결과" })).toHaveText("2개 메뉴를 추천에서 제외했습니다.");
  });
}
