import { expect, test, type Page } from "@playwright/test";

/**
 * 폰 폭(390px)에서 가로로 밀어야 보이는 곳이 없는지 본다. 손가락으로 미는 칩 줄(빠른 카테고리 등)은
 * 일부러 그렇게 만든 것이라 뺀다 — 그 줄도 스크롤바는 숨긴다(mobile.css).
 */
/** 오늘(10/9)에서 거꾸로 n일. 날짜 문자열만 만든다. */
function daysEnding(count: number) {
  const out = [];
  for (let i = count - 1; i >= 0; i--) {
    const d = new Date(Date.UTC(2026, 9, 9) - i * 86_400_000).toISOString().slice(0, 10);
    // 사흘에 하루는 기록이 없고, 닷새에 하루는 모르는 끼니가 있다.
    const empty = i % 3 === 2;
    const [b, l, dn, s] = empty ? [0, 0, 0, 0] : [i % 2 ? 350 : 0, 650 + (i % 4) * 80, 700 - (i % 3) * 90, i % 5 ? 0 : 180];
    const slots = [["BREAKFAST", b], ["LUNCH", l], ["DINNER", dn], ["SNACK", s]]
      .map(([slot, kcal]) => ({ slot, kcal, meals: kcal ? 1 : 0 }));
    const kcal = b + l + dn + s;
    out.push({ date: d, kcal, meals: empty ? 0 : slots.filter((x) => x.meals).length + (i % 5 === 1 ? 1 : 0),
      unknownMeals: !empty && i % 5 === 1 ? 1 : 0, slots,
      // 탄단지는 열량을 대략 55·15·30%로 나눈 값이다.
      macros: empty ? { meals: 0, carbsG: null, proteinG: null, fatG: null }
        : { meals: slots.filter((x) => x.meals).length, carbsG: Math.round(kcal * 0.55 / 4),
          proteinG: Math.round(kcal * 0.15 / 4), fatG: Math.round(kcal * 0.3 / 9) } });
  }
  return out;
}

function summary(periodDays: number) {
  const days = daysEnding(periodDays);
  const known = days.filter((d) => d.kcal > 0);
  const total = known.reduce((sum, d) => sum + d.kcal, 0);
  return {
    periodDays, days,
    slots: ["BREAKFAST", "LUNCH", "DINNER", "SNACK"].map((slot, i) => {
      const kcal = days.reduce((sum, d) => sum + (d.slots[i].kcal as number), 0);
      return { slot, kcal, meals: kcal ? 1 : 0 };
    }),
    totalKcal: total, knownMeals: known.length * 2, unknownMeals: days.reduce((n, d) => n + d.unknownMeals, 0),
    averageKcal: Math.round(total / known.length), truncated: false,
    macros: { meals: known.length * 2, carbsG: 210, proteinG: 72, fatG: 55, fiberMeals: known.length, fiberG: 17,
      sugarsMeals: known.length, sugarsG: 41, sodiumMeals: known.length * 2, sodiumMg: 3120 },
  };
}

async function installApi(page: Page) {
  const requests: Record<string, unknown>[] = [];
  const menu = { id: 1, name: "김치찌개", weight: 3, categories: ["한식"], tags: [],
    isExcluded: false, pausedUntil: null, memo: null, version: 0,
    createdAt: "2026-10-01T12:00:00", updatedAt: "2026-10-01T12:00:00" };
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const data: Record<string, unknown> = {
      "/api/v1/auth/me": { id: 1, email: "e2e@example.com", nickname: "테스터", emailVerified: true },
      "/api/v1/auth/refresh": { accessToken: "mobile-test-token" },
      "/api/v1/onboarding": { needed: false, menus: [] },
      "/api/v1/tags": [{ id: 7, name: "혼밥" }, { id: 9, name: "땅콩" }],
      "/api/v1/pick/preferences": [9],
      "/api/v1/nutrition/reference": { dailyKcal: 2000 },
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
      "/api/v1/history/nutrition": summary(Number(url.searchParams.get("days") ?? 7)),
      "/api/v1/menus/1/nutrition": { menuId: 1, kcalPerServing: null, foodReference: null, version: 0 },
      "/api/v1/history/summary": { periodDays: 30, picks: 1, eaten: 1, categories: [], menus: [], forgottenMenus: [] },
      "/api/v1/history/calendar": { month: url.searchParams.get("month"), entries: [1,1,4,9,9,9,15,20].map((d, i) => ({ id: i + 1, menuName: ["김치찌개","짬뽕","초밥","라멘"][i % 4], restaurantName: "동네 식탁", visitedAt: `${url.searchParams.get("month")}-${String(d).padStart(2,"0")}T12:30:00` })), truncated: false },
      "/api/v1/pick": { historyId: 1, menu, restaurants: [], reasons: [] },
    };
    if (!(path in data)) throw new Error(`처리하지 않은 API 요청: ${path}`);
    if (path === "/api/v1/pick") requests.push(route.request().postDataJSON());
    await route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data: data[path] }) });
  });
  return requests;
}


const SWIPE_STRIPS = ".quick-categories, .applied-conditions, .pick-presets > .chip-row";

async function horizontalOverflow(page: Page) {
  return page.evaluate((strips) => {
    const out: string[] = [];
    const vw = document.documentElement.clientWidth;
    if (document.documentElement.scrollWidth > vw + 1) out.push(`page ${document.documentElement.scrollWidth}>${vw}`);
    for (const el of Array.from(document.querySelectorAll<HTMLElement>("body *"))) {
      if (!el.offsetParent || el.closest(strips)) continue;
      const cs = getComputedStyle(el);
      if ((cs.overflowX === "auto" || cs.overflowX === "scroll") && el.scrollWidth > el.clientWidth + 1)
        out.push(`scroll ${el.className}`);
      if (el.getBoundingClientRect().right > vw + 1) out.push(`over ${el.tagName.toLowerCase()}.${el.className}`);
    }
    return [...new Set(out)];
  }, SWIPE_STRIPS);
}

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
});

for (const path of ["/pick", "/menus", "/restaurants", "/settings"]) {
  test(`${path}: 가로 스크롤 없음`, async ({ page }) => {
    await installApi(page);
    await page.goto(path);
    await expect(page.locator("main")).toBeVisible();
    await expect.poll(() => horizontalOverflow(page)).toEqual([]);
  });
}

test("기록 화면: 열량 그래프 세 모양과 방문 달력이 폭 안에 들어간다", async ({ page }) => {
  await installApi(page);
  await page.goto("/history");
  await page.getByRole("button", { name: "열량 기록", exact: true }).click();
  const section = page.locator(".nutrition-summary");
  for (const kind of ["막대", "선", "원"]) {
    for (const metric of ["열량", "탄단지"]) {
      await section.getByRole("button", { name: kind, exact: true }).click();
      await section.getByRole("button", { name: metric, exact: true }).click();
      await expect.poll(() => horizontalOverflow(page), `${kind}/${metric}`).toEqual([]);
    }
  }
  await expect(section.locator(".nutrition-donut-slice")).toHaveCount(3);
  await section.getByRole("button", { name: "선", exact: true }).click();
  await expect(section.locator(".nutrition-line-path")).toHaveCount(3);

  await page.getByRole("button", { name: /방문 달력/ }).click();
  const calendar = page.locator(".visit-calendar");
  await calendar.getByRole("button", { name: /9일 방문 기록 3개 보기/ }).click();
  await expect(calendar.locator(".calendar-day-detail li")).toHaveCount(3);
  await expect.poll(() => horizontalOverflow(page)).toEqual([]);
});
