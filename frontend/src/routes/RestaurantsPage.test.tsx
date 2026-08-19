import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "../test/renderWithProviders";
import RestaurantsPage from "./RestaurantsPage";
import { fetchRestaurant, fetchRestaurants } from "../api/restaurants";
import { resetKakaoSdkForTest } from "../maps/kakaoSdk";

vi.mock("../api/restaurants", () => ({
  fetchRestaurants: vi.fn(),
  fetchRestaurant: vi.fn(),
  createRestaurant: vi.fn(),
  updateRestaurant: vi.fn(),
  deleteRestaurant: vi.fn(),
}));
vi.mock("../api/places", () => ({ searchPlacesByKeyword: vi.fn() }));
vi.mock("../api/menus", () => ({ fetchMenus: vi.fn().mockResolvedValue({ menus: [] }) }));
vi.mock("../api/menuRestaurants", () => ({ createMenuRestaurant: vi.fn() }));

const fetchRestaurantsMock = vi.mocked(fetchRestaurants);
const fetchRestaurantMock = vi.mocked(fetchRestaurant);

beforeEach(() => {
  resetKakaoSdkForTest();
  delete window.kakao;
  fetchRestaurantsMock.mockReset();
  fetchRestaurantMock.mockReset();
  fetchRestaurantMock.mockRejectedValue(new Error("이 파일은 상세를 보지 않는다"));
});

/**
 * 지도를 얹으면서 지키기로 한 선: <b>지도가 없어도 이 화면은 그대로 쓸 수 있다.</b>
 * 운영 서버에는 아직 VITE_KAKAO_JS_KEY가 없어서, 이게 깨지면 배포 즉시 식당 화면이 죽는다.
 */
describe("VITE_KAKAO_JS_KEY가 없어도", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
  });

  it("저장한 식당 목록은 그대로 보이고 지도 자리에는 안내만 뜬다", async () => {
    fetchRestaurantsMock.mockResolvedValue([
      { id: 1, name: "진주회관", address: "서울시 중구", latitude: 37.5665, longitude: 126.978 },
    ]);

    renderWithProviders(<RestaurantsPage />);

    expect(await screen.findByText("진주회관")).toBeInTheDocument();
    expect(screen.getByText(/지도 키가 설정되지 않아/)).toBeInTheDocument();
    // 장소 검색 폼도 그대로 살아 있어야 한다 — 지도는 부가 기능일 뿐이다.
    expect(screen.getByRole("button", { name: "검색" })).toBeInTheDocument();
  });

  it("좌표 없는 식당도 목록에서는 사라지지 않는다", async () => {
    fetchRestaurantsMock.mockResolvedValue([
      // 서버는 NOT NULL이라 항상 채워 주지만, 옛 응답이 섞여도 목록이 비면 안 된다.
      { id: 2, name: "좌표없는집", address: null } as never,
    ]);

    renderWithProviders(<RestaurantsPage />);

    expect(await screen.findByText("좌표없는집")).toBeInTheDocument();
    // 찍을 좌표가 하나도 없으면 지도 자리 자체를 만들지 않는다
    expect(screen.queryByText(/지도 키가 설정되지 않아/)).not.toBeInTheDocument();
  });
});
