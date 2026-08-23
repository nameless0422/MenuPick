import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import RestaurantsPage from "./RestaurantsPage";
import { fetchRestaurant, fetchRestaurants } from "../api/restaurants";
import { resetKakaoSdkForTest } from "../maps/kakaoSdk";
import { searchPlacesByKeyword } from "../api/places";

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

const searchPlacesMock = vi.mocked(searchPlacesByKeyword);
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

/**
 * 별 묶음을 {@code <label>}로 감싸면 안 된다. {@code <button>}은 labelable 요소라
 * for 없는 label의 대상 컨트롤이 <b>첫 번째 별</b>이 되고, .menu-form label이
 * flex-column이라 label 상자가 폼 전체 폭을 차지한다. 결과적으로 "별점" 글자와
 * 별 오른쪽 빈 영역 전체가 별 1의 클릭 영역이 되어, 스크롤하려고 탭하거나 별 4를
 * 겨냥하다 빗나가면 점수가 조용히 1로 떨어진다. MenusPage의 선호도 위젯도 같다.
 */
describe("메뉴 연결 폼의 별점", () => {
  beforeEach(() => {
    fetchRestaurantsMock.mockResolvedValue([
      { id: 1, name: "진주회관", address: "서울시 중구", latitude: 37.5665, longitude: 126.978 },
    ]);
  });

  it("라벨 영역을 눌러도 점수가 바뀌지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    await user.click(await screen.findByRole("button", { name: "진주회관 메뉴 연결" }));

    const group = await screen.findByRole("group", { name: "별점" });
    await user.click(screen.getByRole("button", { name: "별점 5" }));
    expect(screen.getByRole("button", { name: "별점 5" })).toHaveAttribute("aria-pressed", "true");

    // 별 묶음을 감싼 컨테이너(옛 <label>) 자체를 클릭한다.
    await user.click(group.parentElement!);

    // label이었다면 이 클릭이 별 1로 위임되어 5점이 1점이 된다.
    expect(screen.getByRole("button", { name: "별점 5" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "별점 2" })).toHaveAttribute("aria-pressed", "true");
  });

  it("별 묶음을 감싼 요소가 label이 아니다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    await user.click(await screen.findByRole("button", { name: "진주회관 메뉴 연결" }));

    const group = await screen.findByRole("group", { name: "별점" });
    expect(group.closest("label")).toBeNull();
  });
});

/**
 * 이 화면은 "검색해서 저장한다"가 전부인데, 그 두 컨트롤에 이름이 없었다.
 * 검색 입력은 식당을 추가하는 유일한 진입점이고, 결과의 "저장" 버튼은 결과 수만큼 늘어선다.
 */
describe("검색과 목록의 이름", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
    searchPlacesMock.mockReset();
  });

  it("장소 검색 입력에 이름이 있다 (placeholder는 이름 계산의 최후 폴백일 뿐이다)", async () => {
    fetchRestaurantsMock.mockResolvedValue([]);

    renderWithProviders(<RestaurantsPage />);

    expect(await screen.findByRole("textbox", { name: "장소 검색어" })).toBeInTheDocument();
  });

  it("저장한 식당의 액션 3종이 어느 식당의 것인지 밝힌다", async () => {
    fetchRestaurantsMock.mockResolvedValue([
      { id: 1, name: "진주회관", address: "서울시 중구", latitude: 37.5665, longitude: 126.978 },
    ]);

    renderWithProviders(<RestaurantsPage />);

    expect(await screen.findByRole("button", { name: "진주회관 수정" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "진주회관 메뉴 연결" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "진주회관 삭제" })).toBeInTheDocument();
  });

  it("검색 결과의 저장 버튼이 어느 가게를 저장하는지 밝힌다", async () => {
    const user = userEvent.setup();
    fetchRestaurantsMock.mockResolvedValue([]);
    searchPlacesMock.mockResolvedValue({
      meta: { total_count: 2, pageable_count: 2, is_end: true },
      documents: [
        place("1", "진주회관"),
        place("2", "을지면옥"),
      ],
    });

    renderWithProviders(<RestaurantsPage />);

    await user.type(await screen.findByRole("textbox", { name: "장소 검색어" }), "중구");
    await user.click(screen.getByRole("button", { name: "검색" }));

    // 이름이 없으면 "저장, 저장"만 남아 어느 쪽을 눌렀는지 알 수 없다.
    expect(await screen.findByRole("button", { name: "진주회관 저장" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "을지면옥 저장" })).toBeInTheDocument();
  });
});

function place(id: string, name: string) {
  return {
    id,
    place_name: name,
    address_name: "서울시 중구",
    road_address_name: null,
    x: "126.978",
    y: "37.5665",
    phone: null,
    place_url: null,
    category_name: null,
    category_group_code: null,
    category_group_name: null,
    distance: null,
  };
}

/**
 * 검색 → 저장이 이 화면의 핵심 플로우인데, 둘 다 결과가 통지되지 않았다.
 * "검색"을 눌러도 초점은 버튼에 남고 결과만 화면 아래에 조용히 그려진다.
 */
describe("검색·저장 결과 통지", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
    searchPlacesMock.mockReset();
    fetchRestaurantsMock.mockResolvedValue([]);
  });

  it("검색 결과 건수를 알린다", async () => {
    const user = userEvent.setup();
    searchPlacesMock.mockResolvedValue({
      meta: { total_count: 2, pageable_count: 2, is_end: true },
      documents: [place("1", "진주회관"), place("2", "을지면옥")],
    });

    renderWithProviders(<RestaurantsPage />);

    await user.type(await screen.findByRole("textbox", { name: "장소 검색어" }), "중구");
    await user.click(screen.getByRole("button", { name: "검색" }));

    await waitFor(() =>
      expect(
        screen.getAllByRole("status").some((r) => r.textContent?.includes("2건의 장소를 찾았습니다.")),
      ).toBe(true),
    );
  });

  it("검색 결과가 0건인 것도 알린다", async () => {
    const user = userEvent.setup();
    searchPlacesMock.mockResolvedValue({
      meta: { total_count: 0, pageable_count: 0, is_end: true },
      documents: [],
    });

    renderWithProviders(<RestaurantsPage />);

    await user.type(await screen.findByRole("textbox", { name: "장소 검색어" }), "없는키워드");
    await user.click(screen.getByRole("button", { name: "검색" }));

    // 이 안내는 원래도 화면에 있었지만 라이브 리전 밖이라 통지되지 않았다.
    await waitFor(() =>
      expect(
        screen.getAllByRole("status").some((r) => r.textContent?.includes("검색 결과가 없습니다")),
      ).toBe(true),
    );
  });

  it("저장한 식당 수를 알린다", async () => {
    fetchRestaurantsMock.mockResolvedValue([
      { id: 1, name: "진주회관", address: "서울시 중구", latitude: 37.5665, longitude: 126.978 },
    ]);

    renderWithProviders(<RestaurantsPage />);

    await waitFor(() =>
      expect(
        screen.getAllByRole("status").some((r) => r.textContent?.includes("저장한 식당 1곳")),
      ).toBe(true),
    );
  });
});

/**
 * 삭제 버튼을 누른 그 카드가 사라진다. window.confirm은 브라우저가 원래 버튼으로 초점을
 * 되돌려 주지만, 삭제가 성공하는 순간 그 버튼이 DOM에서 없어져 초점은 결국 {@code <body>}로
 * 떨어진다. 삭제 변이가 카드 안에 있어 <b>카드가 스스로를 언마운트한다</b> — 초점을 받을
 * 자리는 카드보다 오래 사는 부모에 있어야 한다. (HistoryPage와 같은 처리)
 */
describe("RestaurantsPage 삭제 후 초점", () => {
  const JINJU = { id: 1, name: "진주회관", address: "서울시 중구", latitude: 37.5665, longitude: 126.978 };
  const EULJI = { id: 2, name: "을지면옥", address: "서울시 중구", latitude: 37.566, longitude: 126.99 };

  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
  });

  it("식당이 남아 있으면 초점이 목록으로 간다", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    fetchRestaurantsMock.mockResolvedValueOnce([JINJU, EULJI]);
    // 삭제 뒤 다시 불러오면 한 곳만 남는다.
    fetchRestaurantsMock.mockResolvedValue([EULJI]);

    renderWithProviders(<RestaurantsPage />);

    await user.click(await screen.findByRole("button", { name: "진주회관 삭제" }));

    await waitFor(() => expect(screen.getByRole("list")).toHaveFocus());
    // 목록이 다시 그려져도 같은 <ul>이라 초점은 그대로 남아 있어야 한다.
    await waitFor(() => expect(screen.queryByText("진주회관")).not.toBeInTheDocument());
    expect(screen.getByRole("list")).toHaveFocus();
    confirmSpy.mockRestore();
  });

  it("마지막 식당을 지우면 초점이 제목으로 간다", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    fetchRestaurantsMock.mockResolvedValueOnce([JINJU]);
    fetchRestaurantsMock.mockResolvedValue([]);

    renderWithProviders(<RestaurantsPage />);

    await user.click(await screen.findByRole("button", { name: "진주회관 삭제" }));

    // 빈 목록("목록, 항목 0개")은 초점을 둘 자리가 못 된다.
    await waitFor(() => expect(screen.getByRole("heading", { name: "내 식당" })).toHaveFocus());
    await waitFor(() => expect(screen.queryByText("진주회관")).not.toBeInTheDocument());
    expect(screen.getByRole("heading", { name: "내 식당" })).toHaveFocus();
    confirmSpy.mockRestore();
  });

  it("수정 저장은 목록으로 초점을 끌어가지 않는다", async () => {
    const user = userEvent.setup();
    fetchRestaurantsMock.mockResolvedValue([JINJU]);
    fetchRestaurantMock.mockResolvedValue({
      ...JINJU,
      phone: null,
      naverUrl: null,
      kakaoPlaceId: null,
      createdAt: "2026-01-01T00:00:00",
      updatedAt: "2026-01-01T00:00:00",
    });

    renderWithProviders(<RestaurantsPage />);

    await user.click(await screen.findByRole("button", { name: "진주회관 수정" }));
    await user.click(await screen.findByRole("button", { name: "저장" }));

    // 수정도 저장도 목록을 새로 불러오지만 카드는 그대로 남는다. 삭제 신호(onDeleted)를
    // onChanged와 구분하지 않으면 여기서도 초점이 목록으로 끌려가, 방금 고친 식당이
    // 어디였는지 잃는다.
    await waitFor(() => expect(screen.getByRole("button", { name: "진주회관 수정" })).toHaveFocus());
  });
});
