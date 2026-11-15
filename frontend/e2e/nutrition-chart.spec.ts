import { expect, test } from "@playwright/test";

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
      unknownMeals: !empty && i % 5 === 1 ? 1 : 0, slots });
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
    macros: { meals: known.length * 2, carbsG: 210, proteinG: 72, fatG: 55, fiberMeals: known.length, fiberG: 17 },
  };
}

test("열량 기록을 끼니별 막대로 보여 주고 7일·30일을 바꿔 본다", async ({ page }) => {
  const asked: string[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const respond = (data: unknown) => route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data }) });
    if (url.pathname === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (url.pathname === "/api/v1/history/nutrition") {
      asked.push(url.searchParams.get("days") ?? "");
      return respond(summary(Number(url.searchParams.get("days") ?? 7)));
    }
    if (url.pathname === "/api/v1/history/summary") return respond({ periodDays: 30, picks: 0, eaten: 0, categories: [], menus: [], forgottenMenus: [] });
    if (url.pathname === "/api/v1/history/calendar") return respond({ month: url.searchParams.get("month"), entries: [], truncated: false });
    if (url.pathname === "/api/v1/history") return respond({ histories: [], nextCursor: null, hasNext: false });
    throw new Error("처리하지 않은 E2E 요청: " + route.request().method() + " " + url.pathname);
  });
  await page.goto("/history");
  const section = page.locator(".nutrition-summary");
  await expect(section.getByText(/최근 7일 · 하루 평균/)).toBeVisible();
  await expect(section.locator(".nutrition-chart svg")).toBeVisible();
  // 기록 없는 날은 막대가 없다: 7일 중 이틀이 비어 있어 점심 조각은 5개다.
  await expect(section.locator(".nutrition-chart svg .nutrition-seg-lunch")).toHaveCount(5);

  await expect(section.getByRole("list", { name: "탄단지 하루 평균" })).toContainText("탄수화물 약 210g");
  await section.locator(".nutrition-chart-hit").last().hover();
  await expect(section.locator(".nutrition-chart-tooltip")).toContainText("10/9 (금)");

  await section.getByRole("button", { name: "열량 30일" }).click();
  await expect(section.getByRole("button", { name: "열량 30일" })).toHaveAttribute("aria-pressed", "true");
  await expect(section.getByText(/최근 30일 · 하루 평균/)).toBeVisible();
  expect(asked).toEqual(["7", "30"]);

  await section.getByText("숫자로 보기").click();
  await expect(section.getByRole("table", { name: /최근 30일 하루 열량/ }).getByRole("row")).toHaveCount(31);
  // 그래프가 카드 밖으로 넘치지 않는다.
  const card = await section.boundingBox();
  const svg = await section.locator(".nutrition-chart svg").boundingBox();
  expect(svg!.x + svg!.width).toBeLessThanOrEqual(card!.x + card!.width + 1);
});
