import { expect, test, type Page } from "@playwright/test";

async function installApi(page: Page) {
  const tags = [
    { id: 9, name: "땅콩", version: 0 },
    { id: 11, name: "해산물", version: 0 },
    { id: 13, name: "아주 긴 이름으로 만든 기피 재료 태그도 좁은 화면에서 확인하기", version: 0 },
  ];
  let preferences = [9];
  const saves: number[][] = [];
  const picks: Record<string, unknown>[] = [];
  const menu = { id: 1, name: "김치찌개", weight: 3, categories: ["한식"], tags: [], isExcluded: false };
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/pick/preferences" && route.request().method() === "PUT") {
      preferences = route.request().postDataJSON().defaultExcludedTagIds;
      saves.push([...preferences]);
    }
    if (path === "/api/v1/pick") picks.push(route.request().postDataJSON());
    const data: Record<string, unknown> = {
      "/api/v1/auth/refresh": { accessToken: "preferences-test-token" },
      "/api/v1/auth/me": { nickname: "테스터", email: "test@example.com", hasPassword: false, linkedProviders: [] },
      "/api/v1/tags": tags,
      "/api/v1/pick/preferences": preferences,
      "/api/v1/pick/presets": { presets: [], limit: 10 },
      "/api/v1/onboarding": { needed: false, menus: [] },
      "/api/v1/history": { histories: [], hasNext: false, nextCursor: null },
      "/api/v1/trends": { status: "DISABLED", categories: [], menus: [], windowDays: 7 },
      "/api/v1/pick": { historyId: 1, menu, restaurants: [], reasons: [] },
    };
    if (!(path in data)) throw new Error(`처리하지 않은 API 요청: ${path}`);
    await route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data: data[path] }) });
  });
  return { saves, picks };
}

test("설정에서 검색으로 추가한 기본 제외가 다음 픽과 돌아온 설정에 그대로 반영된다", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { saves, picks } = await installApi(page);
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/pick");
  await expect(page.getByRole("button", { name: "제외 태그 땅콩 해제" })).toBeVisible();
  await page.getByRole("button", { name: "계정 메뉴" }).click();
  await page.getByRole("link", { name: "설정 열기", exact: true }).click();
  const section = page.getByRole("region", { name: "기본 제외 태그", exact: true });
  const group = section.getByRole("group", { name: "기본 제외할 태그" });
  await expect(group.getByRole("checkbox", { name: "땅콩", exact: true })).toBeChecked();
  await section.getByRole("searchbox").fill("해산");
  await group.getByRole("checkbox", { name: "해산물", exact: true }).check();
  await section.getByRole("button", { name: "기본 제외 저장" }).click();
  await expect(section.getByText("기본 제외 설정을 저장했습니다.")).toBeVisible();
  expect(saves).toEqual([[9, 11]]);
  await page.getByRole("navigation", { name: "주요 메뉴" }).getByRole("link").first().click();
  await expect(page.getByRole("button", { name: "제외 태그 해산물 해제" })).toBeVisible();
  await expect(page.getByRole("button", { name: "제외 태그 땅콩 해제" })).toBeVisible();
  await page.getByRole("button", { name: "오늘의 메뉴 뽑기" }).click();
  await expect.poll(() => picks.length).toBe(1);
  expect(picks[0].excludeTagIds).toEqual([9, 11]);
  await page.getByRole("button", { name: "계정 메뉴" }).click();
  await page.getByRole("link", { name: "설정 열기", exact: true }).click();
  await expect(group.getByRole("checkbox", { name: "해산물", exact: true })).toBeChecked();
  await expect(group.getByRole("checkbox", { name: "땅콩", exact: true })).toBeChecked();
  expect(errors).toEqual([]);
});

for (const width of [320, 1440]) {
  test(`${width}px에서 긴 태그·검색·선택 보기를 글자 200%로도 조작한다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await installApi(page);
    await page.goto("/settings");
    await page.addStyleTag({ content: ":root { font-size: 200%; }" });
    const section = page.getByRole("region", { name: "기본 제외 태그", exact: true });
    const group = section.getByRole("group", { name: "기본 제외할 태그" });
    await expect(group.getByRole("checkbox", { name: "땅콩", exact: true })).toBeChecked();
    await section.scrollIntoViewIfNeeded();
    await page.evaluate(() => document.fonts.ready);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    for (const label of await group.locator("label").all()) {
      const box = await label.boundingBox();
      expect(box!.height).toBeGreaterThanOrEqual(44);
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(width);
    }
    await section.getByRole("searchbox").fill("없는 이름");
    await expect(section.getByText(/검색 조건에 맞는 태그가 없어요/)).toBeVisible();
    await section.getByRole("button", { name: "검색 지우기" }).click();
    await expect(section.getByRole("searchbox")).toBeFocused();
    const selectedOnly = section.getByRole("checkbox", { name: "선택한 태그만 보기" });
    await selectedOnly.check();
    // 선택 해제 즉시 행이 사라지므로, 사라진 input의 checked 상태를 재조회하지 않는다.
    await group.getByRole("checkbox", { name: "땅콩", exact: true }).click();
    await expect(selectedOnly).toBeFocused();
    await expect(section.getByText(/선택한 태그가 없어요/)).toBeVisible();
    await section.getByRole("button", { name: "변경 취소" }).click();
    await expect(section.getByRole("button", { name: "기본 제외 저장" })).toBeFocused();
    await expect(group.getByRole("checkbox", { name: "땅콩", exact: true })).toBeChecked();
  });
}
