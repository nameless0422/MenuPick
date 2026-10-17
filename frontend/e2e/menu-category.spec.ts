import { expect, test } from "@playwright/test";

test("내 메뉴에서 카테고리와 이름을 함께 검색하고 다음 페이지와 초기화를 처리한다", async ({ page }) => {
  const reads: URL[] = [];
  const menu = (id: number, name: string) => ({ id, name, weight: 1, isExcluded: false,
    categories: ["직접 분류"], tags: [] });
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const respond = (data: unknown) => route.fulfill({ contentType: "application/json",
      body: JSON.stringify({ success: true, data }) });
    if (url.pathname === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (url.pathname === "/api/v1/menus") {
      reads.push(url);
      if (!url.searchParams.has("category")) return respond({ menus: [menu(5, "전체 메뉴")], hasNext: false, nextCursor: null });
      expect(url.searchParams.get("category")).toBe("직접 분류");
      expect(url.searchParams.get("keyword")).toBe("국수");
      return url.searchParams.has("cursor")
        ? respond({ menus: [menu(2, "다음 국수")], hasNext: false, nextCursor: null })
        : respond({ menus: [menu(3, "처음 국수")], hasNext: true, nextCursor: 3 });
    }
    throw new Error("예상하지 않은 요청: " + url.pathname);
  });
  await page.goto("/menus");
  await expect(page.getByText("전체 메뉴", { exact: true })).toBeVisible();
  const before = reads.length;
  await page.getByLabel("조회 카테고리").fill(" 직접 분류 ");
  await page.getByRole("searchbox", { name: "내 메뉴 검색" }).fill(" 국수 ");
  expect(reads).toHaveLength(before);
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await expect(page.getByText("처음 국수", { exact: true })).toBeVisible();
  await expect(page.getByText("적용한 카테고리: 직접 분류")).toBeVisible();
  await page.getByRole("button", { name: "더 보기", exact: true }).click();
  await expect(page.getByText("다음 국수", { exact: true })).toBeVisible();
  expect(reads.at(-1)?.searchParams.get("cursor")).toBe("3");
  await page.getByRole("button", { name: "초기화", exact: true }).click();
  await expect(page.getByText("전체 메뉴", { exact: true })).toBeVisible();
  await expect(page.getByLabel("조회 카테고리")).toHaveValue("");
});
