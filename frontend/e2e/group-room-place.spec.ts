import { expect, test } from "@playwright/test";

test.use({
  geolocation: { latitude: 37.5665, longitude: 126.978 },
  permissions: ["geolocation"],
});

test("방장이 식당을 정하면 방 결과에 남는다", async ({ page }) => {
  let place: { name: string; url: string } | null = null;
  let submittedPlaceId: string | null = null;
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    const reply = (data: unknown) => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ success: true, data }),
    });

    if (path === "/api/v1/auth/refresh" && method === "POST") {
      return reply({ accessToken: "host-token" });
    }
    if (path === "/api/v1/pick/rooms/abc123" && method === "GET") {
      if (request.headers().authorization !== "Bearer host-token") {
        throw new Error("방장 권한을 확인하기 전에 방을 조회했어요");
      }
      return reply({
        code: "abc123", expiresAt: "2026-09-27T18:00:00", menus: [],
        participantCount: 2, canChoosePlace: place === null,
        decision: { menuName: "김치찌개", decidedAt: "2026-09-27T12:00:00", place },
      });
    }
    if (path === "/api/v1/kakao/search/keyword" && method === "GET") {
      return reply({
        meta: { total_count: 1, pageable_count: 1, is_end: true },
        documents: [{
          id: "place-1", place_name: "할매김치찌개", address_name: "서울 중구",
          road_address_name: "서울 중구 세종대로", x: "126.978", y: "37.5665",
          phone: null, place_url: "https://place.map.kakao.com/1",
          category_name: "음식점", category_group_code: "FD6",
          category_group_name: "음식점", distance: "300",
        }],
      });
    }
    if (path === "/api/v1/pick/rooms/abc123/place" && method === "POST") {
      if (request.headers().authorization !== "Bearer host-token") {
        throw new Error("방장 토큰 없이 식당을 저장했어요");
      }
      submittedPlaceId = request.postDataJSON().kakaoPlaceId;
      place = { name: "할매김치찌개", url: "https://place.map.kakao.com/1" };
      return reply({
        code: "abc123", expiresAt: "2026-09-27T18:00:00", menus: [],
        participantCount: 2, canChoosePlace: false,
        decision: { menuName: "김치찌개", decidedAt: "2026-09-27T12:00:00", place },
      });
    }
    throw new Error(`처리하지 않은 E2E API 요청: ${method} ${path}`);
  });

  await page.goto("/rooms/abc123");
  await page.getByRole("button", { name: /근처 김치찌개 식당 찾기/ }).click();
  await page.getByRole("button", { name: "할매김치찌개에서 먹을게요" }).click();
  await expect.poll(() => submittedPlaceId).toBe("place-1");
  await expect(page.getByText("할매김치찌개", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: /카카오맵에서 보기/ })).toHaveAttribute(
    "href", "https://place.map.kakao.com/1",
  );
});
