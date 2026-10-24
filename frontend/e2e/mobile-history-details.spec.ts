import { expect, test, type Page } from "@playwright/test";
import type { HistoryVisitTimeResponse } from "../src/api/history";

async function installApi(page: Page, options: { saveGate?: Promise<void>; failOnce?: boolean; longNames?: boolean } = {}) {
  let memo = "덜 맵게 주문";
  let version = 0;
  let removed = false;
  let visited = false;
  const reads: number[] = [];
  const saves: { memo: string; version: number }[] = [];
  const visits: number[] = [];
  const deletes: number[] = [];
  const detailsReads: string[] = [];
  const visitTimes: { visitedAt: string; version: number }[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const p = url.pathname;
    const respond = (data: unknown) => route.fulfill({ contentType: "application/json",
      body: JSON.stringify({ success: true, data }) });
    if (p === "/api/v1/auth/refresh") return respond({ accessToken: "history-details-test-token" });
    if (p === "/api/v1/history/summary") return respond({ periodDays: 30, picks: 2, eaten: 1, categories: [], menus: [], forgottenMenus: [] });
    if (p === "/api/v1/history/calendar") return respond({ month: url.searchParams.get("month"), entries: [], truncated: false });
    if (p === "/api/v1/history") return respond({ histories: [
      { id: 42, menuId: null, menuName: options.longNames ? "아주 긴 이름으로 기록한 김치찌개 메뉴" : "김치찌개",
        restaurantName: "동네 식탁", isVisited: true, memo,
        recommendedAt: "2026-10-23T12:00:00", visitedAt: "2026-10-23T12:30:00", recommendationFeedback: null,
        filterConditions: [{ filterType: "TAG_EXCLUDE", filterValue: "해산물" }] },
      ...(!removed ? [{ id: 43, menuId: null, menuName: "김치찌개", restaurantName: "다른 식당", isVisited: visited,
        memo: "다른 날의 기록", recommendedAt: "2026-10-22T12:00:00", visitedAt: visited ? "2026-10-22T12:30:00" : null,
        recommendationFeedback: null, filterConditions: [] }] : []),
    ], hasNext: false, nextCursor: null });
    if (p === "/api/v1/history/42/memo" && request.method() === "GET") {
      reads.push(42);
      return respond({ historyId: 42, memo, version });
    }
    if (p === "/api/v1/history/42/memo" && request.method() === "PUT") {
      const body = request.postDataJSON();
      saves.push(body);
      expect(body.version).toBe(version);
      if (saves.length === 1) {
        await options.saveGate;
        if (options.failOnce) return route.fulfill({ status: 503, contentType: "application/json",
          body: JSON.stringify({ success: false, errorCode: "SERVICE_UNAVAILABLE", message: "잠시 후 다시 저장해 주세요." }) });
      }
      memo = body.memo;
      version += 1;
      return respond({ historyId: 42, memo, version });
    }
    if (p === "/api/v1/history/42/visit-time") {
      if (request.method() === "PUT") {
        visitTimes.push(request.postDataJSON());
        return respond({ historyId: 42, visitedAt: request.postDataJSON().visitedAt,
          earliestVisitAt: "2026-10-23T00:00:00", latestVisitAt: "2026-10-24T23:59:59", version: version + 1 });
      }
      detailsReads.push(p);
      return respond({ historyId: 42, visitedAt: "2026-10-23T12:30:00", earliestVisitAt: "2026-10-23T00:00:00",
        latestVisitAt: "2026-10-24T23:59:59", version } satisfies HistoryVisitTimeResponse);
    }
    if (p === "/api/v1/history/43/visit" && request.method() === "PATCH") {
      visits.push(43);
      visited = true;
      return respond(null);
    }
    if (p === "/api/v1/history/43" && request.method() === "DELETE") {
      deletes.push(43);
      removed = true;
      return respond(null);
    }
    throw new Error(`처리하지 않은 API: ${request.method()} ${p}`);
  });
  return { reads, saves, visits, deletes, detailsReads, visitTimes };
}

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: "reduce" });
});

test("메모는 바로 읽고 상세는 키보드로 열며 초안을 접기·화면 폭 변경 후에도 유지한다", async ({ page }) => {
  const state = await installApi(page);
  await page.goto("/history");
  const card = page.locator(".history-card").first();
  const toggle = card.getByRole("button", { name: /기록 상세/ });
  await expect(card.getByText("덜 맵게 주문", { exact: true })).toBeVisible();
  await expect(card.getByRole("button", { name: /메모 수정/ })).toHaveCount(0);
  await expect(card.getByRole("button", { name: /픽 기록 삭제/ })).toHaveCount(0);
  await expect(card.getByRole("button", { name: /방문 처리 취소/ })).toHaveCount(0);
  expect(state.reads).toEqual([]);
  await toggle.focus();
  await page.keyboard.press("Enter");
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  await expect(card.getByText("제외 #해산물", { exact: true })).toBeVisible();
  expect(state.reads).toEqual([]);
  expect(state.detailsReads).toEqual([]);
  const opener = card.getByRole("button", { name: /메모 수정/ });
  await opener.click();
  const input = card.getByRole("textbox", { name: "픽 기록 메모" });
  await expect(input).toBeFocused();
  await input.fill("작성 중인 메모");
  await toggle.click();
  await expect(input).toBeHidden();
  await expect(toggle).toBeFocused();
  await toggle.click();
  await expect(input).toHaveValue("작성 중인 메모");
  await input.focus();
  await page.setViewportSize({ width: 1440, height: 900 });
  await expect(input).toBeFocused();
  await expect(card.getByRole("button", { name: /기록 상세/ })).toHaveCount(0);
  await expect(card.locator(".history-memo-text")).toHaveCount(1);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(input).toHaveValue("작성 중인 메모");
  await card.getByRole("button", { name: "메모 편집 취소" }).click();
  await expect(opener).toBeFocused();
  expect(state.saves).toEqual([]);
  expect(state.reads).toEqual([42]);
});

test("저장 중 접어도 요청은 한 번이며 실패한 초안과 초점을 유지하고 다시 저장한다", async ({ page }) => {
  let release!: () => void;
  const saveGate = new Promise<void>((resolve) => { release = resolve; });
  const state = await installApi(page, { saveGate, failOnce: true });
  await page.goto("/history");
  const card = page.locator(".history-card").first();
  const toggle = card.getByRole("button", { name: /기록 상세/ });
  await toggle.click();
  await card.getByRole("button", { name: /메모 수정/ }).click();
  const input = card.getByRole("textbox", { name: "픽 기록 메모" });
  await input.fill("다음에는 덜 맵게");
  const save = card.getByRole("button", { name: "메모 저장", exact: true });
  await save.click();
  await expect.poll(() => state.saves.length).toBe(1);
  await expect(input).toHaveAttribute("readonly", "");
  await toggle.click();
  release();
  await expect(card.locator('[role="alert"]')).toHaveText("잠시 후 다시 저장해 주세요.");
  await expect(toggle).toBeFocused();
  await toggle.click();
  await expect(input).toHaveValue("다음에는 덜 맵게");
  await save.click();
  await expect(input).toHaveCount(0);
  await expect(card.locator(".history-memo-text")).toHaveText("다음에는 덜 맵게");
  await expect(card.getByRole("button", { name: /메모 수정/ })).toBeFocused();
  expect(state.saves).toEqual([
    { memo: "다음에는 덜 맵게", version: 0 }, { memo: "다음에는 덜 맵게", version: 0 },
  ]);
});

test("같은 이름의 다른 기록에만 방문·삭제를 적용하고 삭제 후 목록으로 초점을 돌린다", async ({ page }) => {
  const state = await installApi(page);
  await page.goto("/history");
  const cards = page.locator(".history-card");
  const second = cards.nth(1);
  await second.getByRole("button", { name: "김치찌개 방문했어요" }).click();
  await expect(second.getByText("방문완료", { exact: true })).toBeVisible();
  expect(state.visits).toEqual([43]);
  await expect(second.getByRole("button", { name: /기록 상세/ })).toHaveAttribute("aria-expanded", "false");
  await second.getByRole("button", { name: /기록 상세/ }).click();
  page.once("dialog", (dialog) => dialog.accept());
  await second.getByRole("button", { name: /픽 기록 삭제/ }).click();
  await expect(cards).toHaveCount(1);
  expect(state.deletes).toEqual([43]);
  await expect(page.locator("ul.card-list")).toBeFocused();
  await expect(cards.first().getByText("덜 맵게 주문", { exact: true })).toBeVisible();
});

for (const width of [320, 390, 1440]) {
  test(`${width}px에서 긴 기록과 상세 편집이 가로로 넘치지 않는다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await installApi(page, { longNames: true });
    await page.goto("/history");
    if (width !== 390) await page.addStyleTag({ content: ":root { font-size: 200%; }" });
    const card = page.locator(".history-card").first();
    await expect(card.getByText("덜 맵게 주문", { exact: true })).toBeVisible();
    if (width <= 760) await card.getByRole("button", { name: /기록 상세/ }).click();
    await card.getByRole("button", { name: /메모 수정/ }).click();
    await expect(card.getByRole("textbox")).toBeVisible();
    await card.getByRole("button", { name: /방문 일시 수정/ }).click();
    await expect(card.locator(width <= 760 ? 'input[type="date"]' : 'input[type="datetime-local"]')).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    for (const field of await card.locator("input:visible, textarea:visible, button:visible").all()) {
      const box = await field.boundingBox();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(width);
      if (width <= 760 && await field.evaluate((element) => element.tagName === "BUTTON")) {
        expect(box!.height).toBeGreaterThanOrEqual(44);
      }
    }
  });
}

test("모바일 날짜·시각의 범위를 검증하고 화면 폭을 바꿔도 한국 시각과 초 단위를 유지해 저장한다", async ({ page }) => {
  const state = await installApi(page);
  await page.goto("/history");
  const card = page.locator(".history-card").first();
  await card.getByRole("button", { name: /기록 상세/ }).click();
  await card.getByRole("button", { name: /방문 일시 수정/ }).click();
  const date = card.getByLabel("방문한 날짜 (한국 시간)", { exact: true });
  const clock = card.getByLabel("방문한 시각 (한국 시간)", { exact: true });
  await expect(date).toHaveAttribute("min", "2026-10-23");
  await expect(clock).toHaveAttribute("min", "00:00:00");
  await date.fill("2026-10-24");
  await expect(clock).toHaveAttribute("max", "23:59:59");
  await clock.fill("13:45:27");
  await date.fill("");
  const save = card.getByRole("button", { name: "방문 일시 저장", exact: true });
  await expect(save).toHaveAttribute("aria-disabled", "true");
  await save.focus();
  await page.keyboard.press("Enter");
  expect(state.visitTimes).toEqual([]);
  await date.fill("2026-10-22");
  await card.getByRole("button", { name: "방문 일시 저장", exact: true }).click();
  expect(state.visitTimes).toEqual([]);
  await date.fill("2026-10-24");
  await page.setViewportSize({ width: 1440, height: 900 });
  await expect(card.getByLabel("방문한 날짜와 시각 (한국 시간)", { exact: true })).toHaveValue("2026-10-24T13:45:27");
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(clock).toHaveValue("13:45:27");
  await card.getByRole("button", { name: "방문 일시 저장", exact: true }).click();
  await expect.poll(() => state.visitTimes.length).toBe(1);
  expect(state.visitTimes).toEqual([{ visitedAt: "2026-10-24T13:45:27", version: 0 }]);
  await expect(card.getByRole("button", { name: /방문 일시 수정/ })).toBeFocused();
});
