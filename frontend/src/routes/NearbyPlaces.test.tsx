import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import NearbyPlaces from "./NearbyPlaces";
import { searchNearbyPlaces, type KakaoPlace } from "../api/places";
import { choosePickPlace } from "../api/history";

vi.mock("../api/places", () => ({ searchNearbyPlaces: vi.fn() }));
vi.mock("../api/history", () => ({ choosePickPlace: vi.fn() }));

const searchMock = vi.mocked(searchNearbyPlaces);
const chooseMock = vi.mocked(choosePickPlace);

const place = (overrides: Partial<KakaoPlace> = {}): KakaoPlace => ({
  id: "p-1",
  place_name: "할매김치찌개",
  address_name: "서울 중구 태평로1가 31",
  road_address_name: "서울 중구 세종대로 110",
  x: "126.978",
  y: "37.5665",
  phone: "02-123-4567",
  place_url: "https://place.map.kakao.com/1",
  category_name: "음식점 > 한식",
  category_group_code: "FD6",
  category_group_name: "음식점",
  distance: "350",
  ...overrides,
});

let getCurrentPosition: ReturnType<typeof vi.fn>;

function allowLocation() {
  getCurrentPosition = vi.fn((ok: PositionCallback) =>
    ok({ coords: { latitude: 37.5665, longitude: 126.978 } } as GeolocationPosition),
  );
  vi.stubGlobal("navigator", { ...navigator, geolocation: { getCurrentPosition } });
}

function render() {
  renderWithProviders(<NearbyPlaces historyId={5} menuId={3} menuName="김치찌개" />);
}

beforeEach(() => {
  searchMock.mockReset();
  chooseMock.mockReset();
  searchMock.mockResolvedValue({
    meta: { total_count: 2, pageable_count: 2, is_end: true },
    documents: [place(), place({ id: "p-2", place_name: "김치명가", distance: "1200", place_url: "javascript:alert(1)" })],
  });
  chooseMock.mockResolvedValue({
    restaurantId: 9, restaurantName: "할매김치찌개", restaurantCreated: true, linkCreated: true,
  });
  allowLocation();
});

const findButton = () => screen.getByRole("button", { name: "📍 근처 김치찌개 식당 찾기" });

describe("근처 식당 찾기", () => {
  /** 결과가 나올 때마다 위치 권한 창이 뜨면 연결 식당만 보려던 사람까지 붙잡는다. */
  it("누르기 전에는 위치도 검색도 요청하지 않는다", () => {
    render();

    expect(findButton()).toBeInTheDocument();
    expect(getCurrentPosition).not.toHaveBeenCalled();
    expect(searchMock).not.toHaveBeenCalled();
  });

  it("누르면 현재 위치 주변을 메뉴 이름으로 찾아 가까운 순으로 보여준다", async () => {
    const user = userEvent.setup();
    render();

    await user.click(findButton());

    const list = await screen.findByRole("list", { name: "근처 김치찌개 식당 2곳, 가까운 순" });
    expect(searchMock).toHaveBeenCalledWith("김치찌개", expect.objectContaining({
      latitude: 37.5665, longitude: 126.978,
    }));
    expect(list).toHaveTextContent("할매김치찌개");
    expect(list).toHaveTextContent("350m");
    expect(list).toHaveTextContent("1.2km");
    // 검색 버튼이 사라지므로 초점을 목록으로 옮긴다.
    expect(list).toHaveFocus();
  });

  /** 카카오가 준 값이어도 링크로 걸기 전에 스킴을 본다. */
  it("안전하지 않은 장소 링크는 걸지 않는다", async () => {
    const user = userEvent.setup();
    render();
    await user.click(findButton());
    await screen.findByRole("list");

    const links = screen.getAllByRole("link");
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute("href", "https://place.map.kakao.com/1");
    expect(links[0]).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("고르면 이 픽에 식당을 기록하고, 무엇이 됐는지 알려준다", async () => {
    const user = userEvent.setup();
    render();
    await user.click(findButton());

    await user.click(await screen.findByRole("button", { name: "할매김치찌개에서 먹을게요" }));

    await waitFor(() => expect(chooseMock).toHaveBeenCalledWith(5, expect.objectContaining({ id: "p-1" })));
    const done = await screen.findByRole("status");
    expect(done).toHaveTextContent("할매김치찌개(으)로 정했어요");
    expect(done).toHaveTextContent("다음엔 거리로 뽑을 때도 후보가 돼요");
    expect(done).toHaveFocus();
  });

  it("이미 연결된 식당이면 그렇다고 말한다", async () => {
    const user = userEvent.setup();
    chooseMock.mockResolvedValue({
      restaurantId: 9, restaurantName: "할매김치찌개", restaurantCreated: false, linkCreated: false,
    });
    render();
    await user.click(findButton());
    await user.click(await screen.findByRole("button", { name: "할매김치찌개에서 먹을게요" }));

    expect(await screen.findByRole("status")).toHaveTextContent("이미 김치찌개에 연결된 식당이에요");
  });

  it("고르기에 실패하면 이유를 보여주고 목록을 그대로 둔다", async () => {
    const user = userEvent.setup();
    chooseMock.mockRejectedValue(new Error("일시적인 오류"));
    render();
    await user.click(findButton());
    await user.click(await screen.findByRole("button", { name: "할매김치찌개에서 먹을게요" }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "할매김치찌개에서 먹을게요" })).toBeInTheDocument();
  });

  it("주변에 없으면 없다고 말한다", async () => {
    const user = userEvent.setup();
    searchMock.mockResolvedValue({ meta: { total_count: 0, pageable_count: 0, is_end: true }, documents: [] });
    render();
    await user.click(findButton());

    expect(await screen.findByRole("status")).toHaveTextContent("반경 2km 안에서 ‘김치찌개’ 식당을 찾지 못했어요");
  });

  it("위치를 거부하면 검색하지 않고, 다시 누를 수 있게 둔다", async () => {
    const user = userEvent.setup();
    getCurrentPosition = vi.fn((_ok: PositionCallback, fail: PositionErrorCallback) =>
      fail({ code: 1 } as GeolocationPositionError),
    );
    vi.stubGlobal("navigator", { ...navigator, geolocation: { getCurrentPosition } });
    render();

    await user.click(findButton());

    expect(await screen.findByRole("alert")).toHaveTextContent("위치를 가져오지 못했어요");
    expect(searchMock).not.toHaveBeenCalled();
    await user.click(findButton());
    expect(getCurrentPosition).toHaveBeenCalledTimes(2);
  });
});
