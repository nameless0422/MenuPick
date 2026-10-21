import { expect, test, type Page } from "@playwright/test";

async function installApi(page: Page) {
  const requests: Record<string, unknown>[] = [];
  const menu = { id: 1, name: "김치찌개", weight: 3, categories: ["한식"], tags: [],
    isExcluded: false, pausedUntil: null, memo: null, version: 0,
    createdAt: "2026-10-01T12:00:00", updatedAt: "2026-10-01T12:00:00" };
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const data: Record<string, unknown> = {
      "/api/v1/auth/refresh": { accessToken: "mobile-test-token" },
      "/api/v1/onboarding": { needed: false, menus: [] },
      "/api/v1/tags": [{ id: 7, name: "혼밥" }, { id: 9, name: "땅콩" }],
      "/api/v1/pick/preferences": [9],
      "/api/v1/pick/presets": { presets: [], limit: 10 },
      "/api/v1/trends": { status: "DISABLED", categories: [], menus: [], windowDays: 7 },
      "/api/v1/menus": { menus: [menu], hasNext: false, nextCursor: null },
      "/api/v1/menus/1": menu,
      "/api/v1/menus/1/restaurants": { menuRestaurants: [] },
      "/api/v1/restaurants": [{ id: 1, name: "동네 식탁", address: "서울 강남구 역삼동", phone: "02-1234-5678",
        latitude: 37.5, longitude: 127, naverUrl: "https://place.map.kakao.com/1" }],
      "/api/v1/restaurants/1": { id: 1, name: "동네 식탁", address: "서울 강남구 역삼동", phone: "02-1234-5678",
        latitude: 37.5, longitude: 127, naverUrl: "https://place.map.kakao.com/1", kakaoPlaceId: "1", version: 0 },
      "/api/v1/history": { histories: [{ id: 1, menuId: 1, menuName: menu.name, restaurantName: "동네 식탁",
        isVisited: true, visitedAt: "2026-10-19T12:30:00", recommendedAt: "2026-10-19T12:00:00",
        memo: null, filterConditions: [], recommendationFeedback: null }], hasNext: false, nextCursor: null },
      "/api/v1/history/summary": { periodDays: 30, picks: 1, eaten: 1, categories: [], menus: [], forgottenMenus: [] },
      "/api/v1/history/calendar": { month: url.searchParams.get("month"), entries: [], truncated: false },
      "/api/v1/pick": { historyId: 1, menu, restaurants: [], reasons: [] },
    };
    if (!(path in data)) throw new Error(`처리하지 않은 API 요청: ${path}`);
    if (path === "/api/v1/pick") requests.push(route.request().postDataJSON());
    await route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data: data[path] }) });
  });
  return requests;
}

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: "reduce" });
});

test("조건을 적용해야 요청이 바뀌고 취소·초기화·기본 제외 해제를 구분한다", async ({ page }) => {
  const requests = await installApi(page);
  await page.goto("/pick");
  const trigger = page.getByRole("button", { name: /조건 설정/ });
  await trigger.click();
  const sheet = page.getByRole("dialog", { name: "픽 조건 설정" });
  await sheet.getByRole("button", { name: "한식", exact: true }).click();
  await sheet.getByRole("group", { name: "포함 태그", exact: true }).getByRole("button", { name: "혼밥 태그 추가", exact: true }).click();
  await sheet.getByRole("radio", { name: "7일 안에 뽑은 건 빼기", exact: true }).click();
  await sheet.getByRole("button", { name: "조건 적용" }).click();
  await expect(trigger).toBeFocused();
  const spin = async (count: number) => {
    await page.getByRole("button", { name: "오늘의 메뉴 뽑기" }).click();
    await expect.poll(() => requests.length).toBe(count);
    await expect(page.getByText("김치찌개", { exact: true })).toBeVisible();
  };
  await spin(1);
  expect(requests[0]).toMatchObject({ categories: ["한식"], tagIds: [7], excludeTagIds: [9], excludeRecentDays: 7 });
  await trigger.click();
  await sheet.getByRole("button", { name: "초기화" }).click();
  await expect(sheet.getByRole("button", { name: "땅콩 태그 선택 해제" })).toBeVisible();
  await page.keyboard.press("Escape");
  await spin(2);
  expect(requests[1]).toEqual(requests[0]);
  await trigger.click();
  await sheet.getByRole("button", { name: "초기화" }).click();
  await sheet.getByRole("button", { name: "조건 적용" }).click();
  await spin(3);
  expect(requests[2]).toEqual({ excludeTagIds: [9] });
  await page.getByRole("button", { name: "제외 태그 땅콩 해제" }).click();
  await spin(4);
  expect(requests[3]).toEqual({ excludeTagIds: [] });
});

test("조건 창은 배경 스크롤과 초점 이탈을 막고 Escape·배경·닫기로 취소한다", async ({ page }) => {
  await installApi(page);
  await page.goto("/pick");
  const trigger = page.getByRole("button", { name: /조건 설정/ });
  const sheet = page.getByRole("dialog");
  for (const method of ["escape", "backdrop", "close"]) {
    await trigger.click();
    await expect(sheet).toBeVisible();
    expect(await page.evaluate(() => document.body.style.overflow)).toBe("hidden");
    await sheet.getByRole("button", { name: "조건 적용" }).focus();
    await page.keyboard.press("Tab");
    expect(await sheet.evaluate((element) => element.contains(document.activeElement))).toBe(true);
    if (method === "escape") await page.keyboard.press("Escape");
    else if (method === "backdrop") await page.mouse.click(5, 5);
    else await sheet.getByRole("button", { name: "닫기" }).click();
    await expect(sheet).toHaveCount(0);
    await expect(trigger).toBeFocused();
    expect(await page.evaluate(() => document.body.style.overflow)).toBe("");
  }
});

test("취소한 위치 요청의 늦은 응답이 거리 조건을 되살리지 않는다", async ({ page }) => {
  const requests = await installApi(page);
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "geolocation", { value: { getCurrentPosition: (success: PositionCallback) => {
      (window as unknown as { finishLocation: () => void }).finishLocation = () => success({ coords: { latitude: 37.5, longitude: 127 } } as GeolocationPosition);
    } } });
  });
  await page.goto("/pick");
  const trigger = page.getByRole("button", { name: /조건 설정/ });
  await trigger.click();
  await page.getByRole("checkbox", { name: /내 위치 기준/ }).check();
  await expect(page.getByRole("button", { name: "위치 확인 중…" })).toBeDisabled();
  await page.keyboard.press("Escape");
  await page.evaluate(() => (window as unknown as { finishLocation: () => void }).finishLocation());
  await trigger.click();
  await expect(page.getByRole("checkbox", { name: /내 위치 기준/ })).not.toBeChecked();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "오늘의 메뉴 뽑기" }).click();
  await expect.poll(() => requests.length).toBe(1);
  expect(requests[0]).toEqual({ excludeTagIds: [9] });
});

test("거리와 반경을 적용한 뒤에만 좌표를 뽑기에 전송한다", async ({ page, context }) => {
  const requests = await installApi(page);
  await context.grantPermissions(["geolocation"]);
  await context.setGeolocation({ latitude: 37.5, longitude: 127 });
  await page.goto("/pick");
  await page.getByRole("button", { name: /조건 설정/ }).click();
  await page.getByRole("checkbox", { name: /내 위치 기준/ }).check();
  await page.getByRole("radio", { name: "1km 이내", exact: true }).click();
  await page.getByRole("button", { name: "조건 적용" }).click();
  await page.getByRole("button", { name: "오늘의 메뉴 뽑기" }).click();
  await expect.poll(() => requests.length).toBe(1);
  expect(requests[0]).toMatchObject({ latitude: 37.5, longitude: 127, maxDistance: 1000 });
});

test("조건 편집 중 PC 폭으로 바꾸면 미적용 초안을 버리고 스크롤 잠금을 해제한다", async ({ page }) => {
  const requests = await installApi(page);
  await page.goto("/pick");
  await page.getByRole("button", { name: /조건 설정/ }).click();
  await page.getByRole("dialog").getByRole("button", { name: "중식", exact: true }).click();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(await page.evaluate(() => document.body.style.overflow)).toBe("");
  await expect(page.getByRole("button", { name: "중식", exact: true })).toHaveAttribute("aria-pressed", "false");
  await page.getByRole("button", { name: "오늘의 메뉴 뽑기" }).click();
  await expect.poll(() => requests.length).toBe(1);
  expect(requests[0]).toEqual({ excludeTagIds: [9] });
});

test("모바일 메뉴 관리에서 수정 후 취소하면 관리 버튼으로 초점이 돌아온다", async ({ page }) => {
  await installApi(page);
  await page.goto("/menus");
  await expect(page.getByRole("button", { name: "김치찌개 수정" })).toHaveCount(0);
  const manage = page.getByRole("button", { name: "김치찌개 관리" });
  await manage.click();
  await page.getByRole("button", { name: "김치찌개 수정" }).click();
  await expect(page.getByRole("heading", { name: "메뉴 수정" })).toBeVisible();
  await page.getByRole("button", { name: "취소", exact: true }).click();
  await expect(manage).toBeFocused();
  await expect(manage).toHaveAttribute("aria-expanded", "false");
});

test("기록을 먼저 읽고 통계와 달력은 필요할 때 펼친다", async ({ page }) => {
  await installApi(page);
  await page.goto("/history");
  await expect(page.getByText("김치찌개", { exact: true })).toBeInViewport();
  await expect(page.getByRole("heading", { name: "내 식사 기록" })).toHaveCount(0);
  await expect(page.getByRole("table")).toHaveCount(0);
  await page.getByRole("button", { name: "식사 통계" }).click();
  await expect(page.getByRole("heading", { name: "내 식사 기록" })).toBeVisible();
  await page.getByRole("button", { name: "방문 달력" }).click();
  await expect(page.getByRole("table")).toBeVisible();
});

test("작은 화면의 200% 글자 크기에서도 조건 적용 버튼과 모든 입력을 사용할 수 있다", async ({ page }) => {
  await installApi(page);
  await page.setViewportSize({ width: 320, height: 640 });
  await page.goto("/pick");
  await page.addStyleTag({ content: ":root { font-size: 200%; }" });
  await page.getByRole("button", { name: /조건 설정/ }).click();
  const sheet = page.getByRole("dialog");
  await expect(sheet.getByRole("button", { name: "조건 적용" })).toBeInViewport({ ratio: 1 });
  await sheet.getByRole("button", { name: "조건 적용" }).click({ trial: true });
  for (const input of await sheet.locator("input:not([type=checkbox])").all()) {
    const box = await input.boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(320);
  }
  await sheet.getByRole("radio", { name: "3일 안에 뽑은 건 빼기", exact: true }).click();
  await sheet.getByRole("button", { name: "조건 적용" }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("화면 키보드로 시각 뷰포트가 줄어들면 적용 버튼도 위로 이동한다", async ({ page }) => {
  await installApi(page);
  await page.goto("/pick");
  await page.getByRole("button", { name: /조건 설정/ }).click();
  await page.evaluate(() => {
    Object.defineProperty(window.visualViewport!, "height", { configurable: true, value: 460 });
    window.visualViewport!.dispatchEvent(new Event("resize"));
  });
  const apply = page.getByRole("button", { name: "조건 적용" });
  await expect.poll(async () => {
    const box = await apply.boundingBox();
    return box!.y + box!.height;
  }).toBeLessThanOrEqual(460);
  await apply.click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("결과를 받은 뒤에도 함께 뽑기와 현재 조건 저장을 열 수 있다", async ({ page }) => {
  await installApi(page);
  await page.goto("/pick");
  await page.getByRole("button", { name: "오늘의 메뉴 뽑기" }).click();
  await expect(page.getByText("김치찌개", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "함께 뽑기 · 조건 저장" }).click();
  await expect(page.getByRole("button", { name: "여럿이 같이 뽑기" })).toBeVisible();
  await page.getByRole("button", { name: "현재 조건 저장", exact: true }).click();
  await expect(page.getByRole("group", { name: "현재 조건을 빠른 픽으로 저장" })).toBeVisible();
});

test("식당 목록을 먼저 보고 추가·관리 작업을 열며 수정 취소 후 초점을 복원한다", async ({ page }) => {
  await installApi(page);
  await page.goto("/restaurants");
  await expect(page.getByText("동네 식탁", { exact: true })).toBeInViewport({ ratio: 1 });
  await expect(page.getByLabel("장소 검색어", { exact: true })).toBeHidden();
  await expect(page.getByRole("button", { name: "동네 식탁 수정", exact: true })).toHaveCount(0);
  const manage = page.getByRole("button", { name: "동네 식탁 관리", exact: true });
  await manage.click();
  await page.getByRole("button", { name: "동네 식탁 수정", exact: true }).click();
  await expect(page.getByLabel("식당 이름", { exact: true })).toHaveValue("동네 식탁");
  await page.getByRole("button", { name: "취소", exact: true }).click();
  await expect(manage).toBeFocused();
  await expect(manage).toHaveAttribute("aria-expanded", "false");
  const add = page.getByRole("button", { name: "새 식당 추가", exact: true });
  await add.click();
  await page.getByLabel("장소 검색어", { exact: true }).fill("역삼동");
  await add.click();
  await add.click();
  await expect(page.getByLabel("장소 검색어", { exact: true })).toHaveValue("역삼동");
});

test("식당 목록은 320px에서 글자를 200%로 키워도 가로로 넘치지 않는다", async ({ page }) => {
  await installApi(page);
  await page.setViewportSize({ width: 320, height: 740 });
  await page.goto("/restaurants");
  await page.addStyleTag({ content: ":root { font-size: 200%; }" });
  await page.evaluate(() => document.fonts.ready);
  await page.getByRole("button", { name: "동네 식탁 관리", exact: true }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole("button", { name: "동네 식탁 수정", exact: true }).click({ trial: true });
});
