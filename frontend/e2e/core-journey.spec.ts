import { expect, test, type Page, type Route } from "@playwright/test";

const now = "2026-09-08T12:00:00+09:00";

const menu = {
  id: 1,
  name: "김치찌개",
  memo: null,
  weight: 3,
  isExcluded: false,
  categories: [],
  tags: [],
  createdAt: now,
  updatedAt: now,
  version: 0,
};

const restaurant = {
  id: 1,
  name: "테스트식당",
  address: "서울 중구 세종대로 110",
  phone: "02-1234-5678",
  latitude: 37.5665,
  longitude: 126.978,
  naverUrl: "https://place.map.kakao.com/1",
  kakaoPlaceId: "place-1",
  createdAt: now,
  updatedAt: now,
  version: 0,
};

function api(route: Route, data: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify({ success: true, data }),
  });
}

async function installFakeApi(page: Page) {
  let menuCreated = false;
  let restaurantCreated = false;
  let linked = false;
  let picked = false;
  let visited = false;

  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    if (path === "/api/v1/auth/signup" && method === "POST") return api(route, null);
    if (path === "/api/v1/auth/verify-email" && method === "POST") {
      return api(route, { accessToken: "e2e-access-token" });
    }
    if (path === "/api/v1/auth/refresh" && method === "POST") {
      return api(route, { accessToken: "e2e-access-token" });
    }
    if (path === "/api/v1/tags" && method === "GET") return api(route, []);
    if (path === "/api/v1/pick/preferences" && method === "GET") return api(route, []);

    if (path === "/api/v1/menus" && method === "POST") {
      menuCreated = true;
      return api(route, menu, 201);
    }
    if (path === "/api/v1/menus" && method === "GET") {
      return api(route, {
        menus: menuCreated ? [menu] : [],
        nextCursor: null,
        hasNext: false,
      });
    }
    if (path === "/api/v1/menus/1/restaurants" && method === "POST") {
      linked = true;
      return api(route, {
        menuId: 1,
        restaurantId: 1,
        restaurantName: restaurant.name,
        rating: 3,
        memo: null,
      }, 201);
    }
    if (path === "/api/v1/menus/1/restaurants" && method === "GET") {
      return api(route, {
        menuRestaurants: linked
          ? [{ menuId: 1, restaurantId: 1, restaurantName: restaurant.name, rating: 3, memo: null }]
          : [],
      });
    }

    if (path === "/api/v1/kakao/search/keyword" && method === "GET") {
      return api(route, {
        meta: { total_count: 1, pageable_count: 1, is_end: true },
        documents: [{
          id: "place-1",
          place_name: restaurant.name,
          address_name: restaurant.address,
          road_address_name: restaurant.address,
          x: String(restaurant.longitude),
          y: String(restaurant.latitude),
          phone: restaurant.phone,
          place_url: restaurant.naverUrl,
          category_name: "음식점 > 한식",
          category_group_code: "FD6",
          category_group_name: "음식점",
          distance: "",
        }],
      });
    }
    if (path === "/api/v1/restaurants" && method === "POST") {
      restaurantCreated = true;
      return api(route, restaurant, 201);
    }
    if (path === "/api/v1/restaurants" && method === "GET") {
      return api(route, restaurantCreated ? [restaurant] : []);
    }
    if (path === "/api/v1/restaurants/1" && method === "GET") {
      return api(route, restaurant);
    }

    if (path === "/api/v1/pick" && method === "POST") {
      if (!menuCreated || !restaurantCreated || !linked) {
        return route.fulfill({ status: 409, contentType: "application/json", body: "{}" });
      }
      picked = true;
      return api(route, {
        historyId: 1,
        menu,
        restaurants: [{
          id: restaurant.id,
          name: restaurant.name,
          address: restaurant.address,
          latitude: restaurant.latitude,
          longitude: restaurant.longitude,
          distance: null,
        }],
        reasons: ["선호도 3점을 반영했어요"],
      });
    }
    if (path === "/api/v1/history" && method === "GET") {
      return api(route, {
        histories: picked ? [{
          id: 1,
          menuName: menu.name,
          restaurantName: restaurant.name,
          isVisited: visited,
          recommendedAt: now,
          visitedAt: visited ? now : null,
          filterConditions: [],
        }] : [],
        nextCursor: null,
        hasNext: false,
      });
    }
    if (path === "/api/v1/history/1/visit" && method === "PATCH") {
      visited = true;
      return api(route, null);
    }

    throw new Error(`처리하지 않은 E2E API 요청: ${method} ${path}`);
  });

  return { isVisited: () => visited };
}

test("가입부터 방문 처리까지 핵심 사용자 여정을 완료한다", async ({ page }) => {
  const state = await installFakeApi(page);

  await page.goto("/signup");
  await page.getByLabel("이메일").fill("e2e@example.com");
  await page.getByLabel("닉네임").fill("테스터");
  await page.getByLabel("비밀번호", { exact: true }).fill("password123!");
  await page.getByLabel("비밀번호 확인").fill("password123!");
  await page.getByRole("button", { name: "가입하기" }).click();
  await expect(page.getByRole("heading", { name: "메일을 확인해주세요" })).toBeVisible();

  await page.goto("/verify-email?token=e2e-token");
  await expect(page).toHaveURL(/\/menus$/);

  await page.getByRole("button", { name: "+ 새 메뉴" }).click();
  await page.getByLabel("메뉴 이름").fill(menu.name);
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(page.getByText(menu.name, { exact: true })).toBeVisible();

  await page.getByRole("link", { name: "식당", exact: true }).click();
  await page.getByRole("textbox", { name: "장소 검색어" }).fill(restaurant.name);
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await page.getByRole("button", { name: `${restaurant.name} 저장` }).click();
  await expect(page.getByRole("button", { name: `${restaurant.name} 메뉴 연결` })).toBeVisible();

  await page.getByRole("button", { name: `${restaurant.name} 메뉴 연결` }).click();
  await page.getByRole("button", { name: menu.name, exact: true }).click();
  await page.getByRole("button", { name: "연결", exact: true }).click();
  await expect(page.getByRole("button", { name: `${restaurant.name} 메뉴 연결` })).toBeVisible();

  await page.getByRole("link", { name: "오늘 뭐 먹지" }).click();
  await page.getByRole("button", { name: "오늘의 메뉴 뽑기" }).click();
  await expect(page.getByText(menu.name, { exact: true })).toBeVisible();

  await page.getByRole("link", { name: "히스토리", exact: true }).click();
  await page.getByRole("button", { name: `${menu.name} 방문했어요` }).click();
  await expect.poll(state.isVisited).toBe(true);
  await expect(page.getByText("방문완료", { exact: true })).toBeVisible();
});
