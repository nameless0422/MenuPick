import { expect, test } from "@playwright/test";

test("태그 이름을 바꿔도 기본 제외를 유지하고 다른 탭 수정과 충돌하면 초안을 보존한다", async ({ page }) => {
  let tag = { id: 7, name: "견과류오타", version: 0, createdAt: "2026-10-01T00:00:00" };
  const submitted: { name: string; version: number }[] = [];
  let conflict = false;
  let unexpectedWrites = 0;
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const respond = (data: unknown) => route.fulfill({
      contentType: "application/json", body: JSON.stringify({ success: true, data }),
    });
    if (url.pathname === "/api/v1/auth/refresh") return respond({ accessToken: "e2e-token" });
    if (url.pathname === "/api/v1/auth/me") return respond({
      email: "e2e@example.com", nickname: "테스터", hasPassword: true, linkedProviders: [],
    });
    if (url.pathname === "/api/v1/tags" && request.method() === "GET") return respond([tag]);
    if (url.pathname === "/api/v1/pick/preferences" && request.method() === "GET") return respond([7]);
    if (url.pathname === "/api/v1/tags/7" && request.method() === "PATCH") {
      const body = request.postDataJSON();
      submitted.push(body);
      if (conflict) {
        conflict = false;
        tag = { ...tag, name: "다른 탭에서 고친 이름", version: tag.version + 1 };
        return route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({
          success: false, errorCode: "CONCURRENT_MODIFICATION", message: "다른 곳에서 수정됐습니다.",
        }) });
      }
      expect(body.version).toBe(tag.version);
      tag = { ...tag, name: body.name, version: tag.version + 1 };
      return respond(tag);
    }
    unexpectedWrites += 1;
    throw new Error(`처리하지 않은 요청: ${request.method()} ${url.pathname}`);
  });
  await page.goto("/settings");
  await expect(page.getByRole("checkbox", { name: "견과류오타", exact: true })).toBeChecked();
  await page.getByRole("button", { name: "견과류오타 태그 이름 변경", exact: true }).click();
  const input = page.getByRole("textbox", { name: "새 태그 이름" });
  await expect(input).toBeFocused();
  await input.fill("견과류");
  await page.getByRole("button", { name: "이름 저장", exact: true }).click();
  await expect(page.getByRole("button", { name: "견과류 태그 이름 변경", exact: true })).toBeFocused();
  await expect(page.getByRole("checkbox", { name: "견과류", exact: true })).toBeChecked();
  expect(submitted).toEqual([{ name: "견과류", version: 0 }]);

  await page.getByRole("button", { name: "견과류 태그 이름 변경", exact: true }).click();
  await input.fill("내가 작성한 이름");
  conflict = true;
  await page.getByRole("button", { name: "이름 저장", exact: true }).click();
  await expect(page.getByText(/입력한 이름은 유지했습니다/)).toBeVisible();
  await expect(input).toHaveValue("내가 작성한 이름");
  expect(submitted[1]).toEqual({ name: "내가 작성한 이름", version: 1 });
  await page.getByRole("button", { name: "이름 변경 취소" }).click();
  await page.getByRole("button", { name: "다른 탭에서 고친 이름 태그 이름 변경", exact: true }).click();
  await expect(input).toHaveValue("다른 탭에서 고친 이름");
  await input.fill("최종 이름");
  await page.getByRole("button", { name: "이름 저장", exact: true }).click();
  await expect(page.getByRole("checkbox", { name: "최종 이름", exact: true })).toBeChecked();
  expect(submitted[2]).toEqual({ name: "최종 이름", version: 2 });
  expect(unexpectedWrites).toBe(0);
});
