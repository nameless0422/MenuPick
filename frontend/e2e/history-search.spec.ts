import { expect, test } from "@playwright/test";

test("식당 이름으로 검색한 기록을 더 보고 방문 처리한 뒤 검색을 초기화한다", async ({ page }) => {
  const histories = [
    { id: 42, menuId: null, menuName: "김치찌개", restaurantName: "진주회관", isVisited: false,
      recommendedAt: "2026-10-09T12:00:00", visitedAt: null, recommendationFeedback: null, filterConditions: [] },
    { id: 7, menuId: null, menuName: "비빔밥", restaurantName: "진주회관", isVisited: false,
      recommendedAt: "2026-10-08T12:00:00", visitedAt: null, recommendationFeedback: null, filterConditions: [] },
    { id: 2, menuId: null, menuName: "파스타", restaurantName: "다른 식당", isVisited: false,
      recommendedAt: "2026-10-07T12:00:00", visitedAt: null, recommendationFeedback: null, filterConditions: [] },
  ];
  const requests: { keyword: string | null; cursor: string | null; days: string | null; visited: string | null }[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const respond = (data: unknown) => route.fulfill({ contentType: "application/json", body: JSON.stringify({ success: true, data }) });
    if (url.pathname === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (url.pathname === "/api/v1/history/summary") return respond({ periodDays: 30, picks: 0, eaten: 0, categories: [], menus: [], forgottenMenus: [] });
    if (url.pathname === "/api/v1/history/calendar") return respond({ month: url.searchParams.get("month"), entries: [], truncated: false });
    if (url.pathname === "/api/v1/history/42/visit" && request.method() === "PATCH") {
      histories[0].isVisited = true;
      return respond(null);
    }
    if (url.pathname === "/api/v1/history") {
      const params = url.searchParams;
      const keyword = params.get("keyword");
      const cursor = params.get("cursor");
      requests.push({ keyword, cursor, days: params.get("days"), visited: params.get("visited") });
      const results = histories.filter((h) => (!keyword || h.menuName.includes(keyword) || h.restaurantName.includes(keyword))
        && (params.get("visited") !== "false" || !h.isVisited) && (!cursor || h.id < Number(cursor)));
      const shown = keyword ? results.slice(0, 1) : results;
      const hasNext = shown.length < results.length;
      return respond({ histories: shown, nextCursor: hasNext ? shown[shown.length - 1].id : null, hasNext });
    }
    throw new Error(`처리하지 않은 E2E 요청: ${request.method()} ${url.pathname}`);
  });

  await page.goto("/history");
  await expect(page.getByText("파스타", { exact: true })).toBeVisible();
  await page.getByRole("group", { name: "조회 기간" }).getByRole("button", { name: "30일", exact: true }).click();
  await page.getByRole("group", { name: "방문 상태" }).getByRole("button", { name: "미방문", exact: true }).click();
  await expect.poll(() => requests.at(-1)?.visited).toBe("false");
  const calls = requests.length;
  await page.getByRole("searchbox", { name: "픽 기록 검색" }).fill(" 회관 ");
  expect(requests.length).toBe(calls);
  await page.getByRole("button", { name: "기록 검색", exact: true }).click();
  await expect(page.getByText("검색 결과 픽 기록 1개")).toBeVisible();
  await expect(page.getByText("파스타", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "더 보기", exact: true }).click();
  await expect(page.getByText("비빔밥", { exact: true })).toBeVisible();
  expect(requests.at(-1)).toEqual({ keyword: "회관", cursor: "42", days: "30", visited: "false" });
  await page.getByRole("button", { name: "김치찌개 방문했어요", exact: true }).click();
  await expect(page.getByText("김치찌개", { exact: true })).toHaveCount(0);
  await expect(page.getByText("비빔밥", { exact: true })).toBeVisible();
  await expect(page.getByRole("searchbox")).toHaveValue(" 회관 ");
  await page.getByRole("button", { name: "검색 초기화", exact: true }).click();
  await expect(page.getByText("파스타", { exact: true })).toBeVisible();
  await expect(page.getByRole("searchbox")).toHaveValue("");
  await expect(page.getByRole("group", { name: "조회 기간" }).getByRole("button", { name: "30일", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("group", { name: "방문 상태" }).getByRole("button", { name: "미방문", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
});
