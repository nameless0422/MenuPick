import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import RestaurantsPage from "./RestaurantsPage";
import { fetchRestaurant, fetchRestaurants, updateRestaurant, type RestaurantDetail } from "../api/restaurants";
import { resetKakaoSdkForTest } from "../maps/kakaoSdk";
import { searchPlacesByKeyword } from "../api/places";
import { fetchMenus } from "../api/menus";
import { createMenuRestaurant } from "../api/menuRestaurants";
import { focusManager, onlineManager } from "@tanstack/react-query";
import { AxiosError } from "axios";

vi.mock("../api/restaurants", () => ({
  fetchRestaurants: vi.fn(),
  fetchRestaurant: vi.fn(),
  createRestaurant: vi.fn(),
  updateRestaurant: vi.fn(),
  deleteRestaurant: vi.fn(),
  fetchDeletedRestaurants: vi.fn(),
  restoreRestaurant: vi.fn(),
}));
vi.mock("../api/places", () => ({ searchPlacesByKeyword: vi.fn() }));
vi.mock("../api/menus", () => ({ fetchMenus: vi.fn().mockResolvedValue({ menus: [] }) }));
vi.mock("../api/menuRestaurants", () => ({ createMenuRestaurant: vi.fn() }));

const searchPlacesMock = vi.mocked(searchPlacesByKeyword);
const fetchRestaurantsMock = vi.mocked(fetchRestaurants);
const fetchRestaurantMock = vi.mocked(fetchRestaurant);
const updateRestaurantMock = vi.mocked(updateRestaurant);
const fetchMenusMock = vi.mocked(fetchMenus);
const createMenuRestaurantMock = vi.mocked(createMenuRestaurant);

/** 끝나지 않는 요청. "요청이 나가 있는 동안"의 화면을 붙잡아 두려면 이게 필요하다. */
function pendingForever<T>() {
  return new Promise<T>(() => {});
}

beforeEach(() => {
  focusManager.setFocused(undefined);
  onlineManager.setOnline(true);
  resetKakaoSdkForTest();
  delete window.kakao;
  fetchRestaurantsMock.mockReset();
  fetchRestaurantMock.mockReset();
  updateRestaurantMock.mockReset();
  createMenuRestaurantMock.mockReset();
  // 메뉴 연결 폼의 메뉴 목록. 기본은 "등록된 메뉴가 없다"이고, 필요한 테스트만 채운다 —
  // 여기서 되돌려 놓지 않으면 한 테스트에서 채운 목록이 다음 테스트로 새어 나간다.
  fetchMenusMock.mockReset();
  fetchMenusMock.mockResolvedValue({ menus: [], nextCursor: null, hasNext: false });
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
      version: 0,
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

const JINJU = { id: 1, name: "진주회관", address: "서울시 중구", latitude: 37.5665, longitude: 126.978 };

/** 카드의 "수정"은 상세가 도착해야 열린다 — 목록에 없는 전화·링크까지 채운 응답. */
const jinjuDetail = {
  ...JINJU,
  phone: null,
  naverUrl: null,
  kakaoPlaceId: null,
  createdAt: "2026-01-01T00:00:00",
  updatedAt: "2026-01-01T00:00:00",
  version: 0,
};

describe("저장한 식당 검색", () => {
  const gangnam = { ...JINJU, id: 2, name: "강남식당", address: "서울 강남구" };

  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
    fetchRestaurantMock.mockResolvedValue(jinjuDetail);
    fetchRestaurantsMock.mockImplementation(async (keyword) => keyword ? [JINJU] : [JINJU, gangnam]);
  });

  it("입력 중에는 조회하지 않고 제출할 때 공백을 제거한 검색어로 조회한다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);
    await screen.findByText("강남식당");
    const input = screen.getByRole("searchbox", { name: "저장한 식당 검색" });
    expect(input).toHaveAttribute("maxlength", "100");
    await user.type(input, "  중구  ");
    expect(fetchRestaurantsMock).toHaveBeenCalledTimes(1);
    await user.keyboard("{Enter}");
    expect(await screen.findByText("‘중구’ 검색 결과 1곳")).toBeInTheDocument();
    expect(fetchRestaurantsMock).toHaveBeenLastCalledWith("중구");
    expect(screen.queryByText("강남식당")).not.toBeInTheDocument();
  });

  it("초기화하면 전체 목록을 다시 표시하고 검색창으로 초점을 옮긴다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);
    await screen.findByText("강남식당");
    const input = screen.getByRole("searchbox", { name: "저장한 식당 검색" });
    await user.type(input, "중구{Enter}");
    await screen.findByText("‘중구’ 검색 결과 1곳");
    await user.click(screen.getByRole("button", { name: "검색 초기화" }));
    expect(await screen.findByText("강남식당")).toBeInTheDocument();
    expect(input).toHaveValue("");
    expect(input).toHaveFocus();
    expect(fetchRestaurantsMock).toHaveBeenLastCalledWith(undefined);
  });

  it("검색 결과가 없을 때 식당 미등록 안내와 구분한다", async () => {
    const user = userEvent.setup();
    fetchRestaurantsMock.mockImplementation(async (keyword) => keyword ? [] : [JINJU]);
    renderWithProviders(<RestaurantsPage />);
    await screen.findByText("진주회관");
    await user.type(screen.getByRole("searchbox", { name: "저장한 식당 검색" }), "없는가게{Enter}");
    expect(await screen.findByText(/‘없는가게’ 검색 결과가 없습니다/)).toBeInTheDocument();
    expect(screen.queryByText(/저장한 식당이 없습니다/)).not.toBeInTheDocument();
    expect(screen.queryByText("진주회관")).not.toBeInTheDocument();
  });

  it("이전 검색 응답이 늦게 와도 새 검색 결과를 바꾸지 않는다", async () => {
    const user = userEvent.setup();
    let finishFirst!: (rows: typeof JINJU[]) => void;
    const first = new Promise<typeof JINJU[]>((resolve) => { finishFirst = resolve; });
    fetchRestaurantsMock.mockImplementation(async (keyword) => keyword === "중구" ? first : keyword ? [gangnam] : [JINJU, gangnam]);
    renderWithProviders(<RestaurantsPage />);
    await screen.findByText("진주회관");
    const input = screen.getByRole("searchbox", { name: "저장한 식당 검색" });
    await user.type(input, "중구{Enter}");
    await waitFor(() => expect(fetchRestaurantsMock).toHaveBeenCalledWith("중구"));
    await user.clear(input);
    await user.type(input, "강남{Enter}");
    await screen.findByText("‘강남’ 검색 결과 1곳");
    await act(async () => { finishFirst([JINJU]); await first; });
    expect(screen.getByText("강남식당")).toBeInTheDocument();
    expect(screen.queryByText("진주회관")).not.toBeInTheDocument();
  });

  it("검색 실패 후 같은 검색어로 다시 조회할 수 있다", async () => {
    const user = userEvent.setup();
    fetchRestaurantsMock.mockResolvedValueOnce([JINJU]).mockRejectedValueOnce(new Error("검색 조회 실패"));
    renderWithProviders(<RestaurantsPage />);
    await screen.findByText("진주회관");
    await user.type(screen.getByRole("searchbox", { name: "저장한 식당 검색" }), "중구{Enter}");
    expect(await screen.findByRole("alert")).toHaveTextContent("검색 조회 실패");
    fetchRestaurantsMock.mockResolvedValue([JINJU]);
    await user.click(screen.getByRole("button", { name: "식당 목록 다시 불러오기" }));
    expect(await screen.findByText("‘중구’ 검색 결과 1곳")).toBeInTheDocument();
    expect(fetchRestaurantsMock).toHaveBeenLastCalledWith("중구");
  });

  it("수정한 식당이 검색 결과에서 빠지면 검색창으로 초점을 옮긴다", async () => {
    const user = userEvent.setup();
    fetchRestaurantsMock.mockResolvedValueOnce([JINJU]).mockResolvedValueOnce([JINJU]).mockResolvedValue([]);
    updateRestaurantMock.mockResolvedValue({ ...jinjuDetail, address: "서울 종로구", version: 1 });
    renderWithProviders(<RestaurantsPage />);
    await screen.findByText("진주회관");
    const search = screen.getByRole("searchbox", { name: "저장한 식당 검색" });
    await user.type(search, "중구{Enter}");
    await screen.findByText("‘중구’ 검색 결과 1곳");
    await user.click(screen.getByRole("button", { name: "진주회관 수정" }));
    await user.clear(screen.getByLabelText("주소"));
    await user.type(screen.getByLabelText("주소"), "서울 종로구");
    await user.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByText(/‘중구’ 검색 결과가 없습니다/);
    await waitFor(() => expect(search).toHaveFocus());
  });

  it("수정 응답을 기다리며 사용자가 옮긴 초점은 유지한다", async () => {
    const user = userEvent.setup();
    fetchRestaurantsMock.mockResolvedValueOnce([JINJU]).mockResolvedValueOnce([JINJU]).mockResolvedValue([]);
    let finishSave!: (detail: typeof jinjuDetail) => void;
    const saving = new Promise<typeof jinjuDetail>((resolve) => { finishSave = resolve; });
    updateRestaurantMock.mockReturnValue(saving);
    renderWithProviders(<RestaurantsPage />);
    await screen.findByText("진주회관");
    await user.type(screen.getByRole("searchbox", { name: "저장한 식당 검색" }), "중구{Enter}");
    await screen.findByText("‘중구’ 검색 결과 1곳");
    await user.click(screen.getByRole("button", { name: "진주회관 수정" }));
    await user.click(screen.getByRole("button", { name: "저장" }));
    const placeSearch = screen.getByRole("textbox", { name: "장소 검색어" });
    await user.click(placeSearch);
    await act(async () => { finishSave(jinjuDetail); await saving; });
    await screen.findByText(/‘중구’ 검색 결과가 없습니다/);
    expect(placeSearch).toHaveFocus();
  });
});

/**
 * 식당 수정 폼의 저장 버튼은 {@code disabled={isPending || !name.trim()}}이었다.
 *
 * <p>이름을 지우는 순간 버튼이 Tab 순회에서 통째로 빠져, 키보드·스크린리더 사용자는 저장
 * 버튼이 있다는 사실도 왜 눌리지 않는지도 알 수 없다. 누른 뒤에는 방금 누른 버튼이
 * disabled가 되며 초점이 {@code <body>}로 떨어진다. aria-disabled·aria-busy는 초점을 뺏지
 * 않는 대신 클릭을 막지 못하므로, 조기 반환이 form onSubmit에 없으면 요청이 그대로 나간다.
 */
describe("식당 수정 폼 저장 버튼", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
    fetchRestaurantsMock.mockResolvedValue([JINJU]);
    fetchRestaurantMock.mockResolvedValue(jinjuDetail);
    updateRestaurantMock.mockResolvedValue(jinjuDetail);
  });

  /** 수정 폼을 열고 저장 버튼을 돌려준다. */
  async function openEditForm(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole("button", { name: "진주회관 수정" }));
    await screen.findByRole("heading", { name: "식당 수정" });
    return screen.getByRole("button", { name: "저장" });
  }

  it("이름을 지워도 저장 버튼이 초점을 받고 '사용 불가'로 읽힌다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    const submit = await openEditForm(user);
    await user.clear(screen.getByLabelText("식당 이름"));
    expect(submit).toHaveAttribute("aria-disabled", "true");

    // disabled였다면 focus()가 조용히 무시되어 초점이 <body>에 남는다.
    submit.focus();
    expect(submit).toHaveFocus();
  });

  // 검색은 이 화면에서 식당을 추가하는 유일한 진입점이다. 검색어 미입력으로 disabled를
  // 걸면 그 버튼이 Tab 순회에서 통째로 빠져, 검색 수단이 있다는 사실조차 전달되지 않는다.
  it("검색어를 안 넣어도 검색 버튼이 초점을 받고, 누르면 이유가 통지된다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    const search = await screen.findByRole("button", { name: "검색" });
    expect(search).toBeEnabled();
    expect(search).toHaveAttribute("aria-disabled", "true");

    await user.click(search);

    expect(await screen.findByRole("alert")).toHaveTextContent("검색어를 입력해주세요.");
    expect(screen.getByRole("textbox", { name: "장소 검색어" })).toHaveFocus();
    expect(searchPlacesMock).not.toHaveBeenCalled();
  });

  // 폼이 noValidate라 완전히 빈 칸도 브라우저가 가로채지 않고 우리 핸들러까지 온다.
  // 켜 두면 "완전히 빈 칸"은 브라우저 말풍선, "공백만 남긴 칸"은 우리 알림으로 갈려
  // 같은 실수인데 화면이 다르게 반응했다.
  it("이름을 통째로 지우고 누르면 같은 안내가 나오고 요청도 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    const submit = await openEditForm(user);
    const nameInput = screen.getByLabelText("식당 이름");
    await user.clear(nameInput);
    await user.click(submit);

    expect(updateRestaurantMock).not.toHaveBeenCalled();
    expect(await screen.findByRole("alert")).toHaveTextContent("식당 이름을 입력해주세요.");
    expect(nameInput).toHaveFocus();
  });

  // required는 빈 칸만 잡는다. 공백만 남긴 값은 required를 통과하지만 name.trim()에는
  // 걸리므로, 우리가 알리지 않으면 눌러도 아무 일이 없는 것처럼 보인다.
  it("공백만 남기고 누르면 수정 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    const submit = await openEditForm(user);
    const nameInput = screen.getByLabelText("식당 이름");
    await user.clear(nameInput);
    await user.type(nameInput, "   ");
    await user.click(submit);

    expect(updateRestaurantMock).not.toHaveBeenCalled();
  });

  it("공백만 남기고 누르면 이유가 통지되고 초점이 이름 칸으로 간다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    const submit = await openEditForm(user);
    const nameInput = screen.getByLabelText("식당 이름");
    await user.clear(nameInput);
    await user.type(nameInput, "   ");
    await user.click(submit);

    expect(await screen.findByRole("alert")).toHaveTextContent("식당 이름을 입력해주세요.");
    // 이유만 읽히고 초점이 버튼에 남으면, 고칠 칸까지 가는 길은 사용자가 직접 찾아야 한다.
    expect(nameInput).toHaveFocus();
    expect(submit).toHaveAccessibleDescription("식당 이름을 입력해주세요.");
  });

  // 제출 경로가 버튼 클릭만이 아니다 — 이름·주소·전화 칸에서 Enter를 쳐도 같은 form이
  // 제출된다. 버튼 onClick에만 조기 반환을 두면 이 경로로 그대로 새어 나간다.
  it("공백만 남기고 입력칸에서 Enter를 쳐도 수정 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    await openEditForm(user);
    const nameInput = screen.getByLabelText("식당 이름");
    await user.clear(nameInput);
    await user.type(nameInput, "   {Enter}");

    expect(updateRestaurantMock).not.toHaveBeenCalled();
    // 알림이 떴다는 것은 제출이 실제로 일어났고 form 쪽에서 막혔다는 뜻이다.
    expect(await screen.findByRole("alert")).toHaveTextContent("식당 이름을 입력해주세요.");
  });

  it("이름이 남아 있으면 잠기지 않고 저장할 수 있다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    const submit = await openEditForm(user);
    expect(submit).not.toHaveAttribute("aria-disabled");
    // 사유가 없는데 참조가 남아 있으면 스크린리더가 빈 설명을 읽는다.
    expect(submit).not.toHaveAttribute("aria-describedby");

    await user.click(submit);
    await waitFor(() => expect(updateRestaurantMock).toHaveBeenCalledTimes(1));
  });

  it("저장 중에도 초점을 지키고 연타되지 않는다", async () => {
    const user = userEvent.setup();
    updateRestaurantMock.mockReturnValue(pendingForever<typeof jinjuDetail>());
    renderWithProviders(<RestaurantsPage />);

    await user.click(await openEditForm(user));

    const busyButton = await screen.findByRole("button", { name: "저장 중…" });
    expect(busyButton).toHaveAttribute("aria-busy", "true");
    expect(busyButton).toHaveFocus();

    // 초점이 남아 있으니 연타가 가능해졌다 — 조기 반환이 없으면 그대로 두 번 나간다.
    await user.click(busyButton);
    expect(updateRestaurantMock).toHaveBeenCalledTimes(1);
  });
});

/**
 * 메뉴 연결 버튼은 {@code disabled={isPending || menuId == null}}이었다.
 *
 * <p>여기서 menuId == null은 성격이 다른 두 상황을 한 조건으로 덮는다. <b>등록된 메뉴가
 * 하나도 없는 경우</b>는 사용자가 이 폼 안에서 풀 수 없는 조건이라, 버튼이 Tab 순회에서
 * 빠지면 왜 막혔는지 알 길이 영영 없다. <b>메뉴는 있는데 아직 안 고른 경우</b>는 지금
 * 여기서 풀 수 있으므로 무엇을 하라는 것인지가 닿아야 한다.
 *
 * <p>연결 요청을 막는 검증은 버튼 클릭뿐 아니라 form onSubmit에도 적용한다.
 */
describe("메뉴 연결 버튼", () => {
  const KIMCHI = { id: 7, name: "김치찌개", weight: 3, isExcluded: false, categories: [], tags: [] };

  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
    fetchRestaurantsMock.mockResolvedValue([JINJU]);
    // 상세도 성공시킨다. 파일 전체의 기본값은 "상세를 보지 않는다"는 뜻의 거부인데,
    // 이제 상세 조회가 실패하면 카드가 role="alert"로 실패 사유를 띄운다 — 여기 테스트가
    // 찾는 알림("연결할 메뉴를 먼저 선택해주세요.")과 섞여 findByRole("alert")이 여럿을
    // 집는다. 이 describe가 보려는 것은 연결 폼이지 상세 조회가 아니다.
    fetchRestaurantMock.mockResolvedValue(jinjuDetail);
  });

  /** 메뉴 연결 폼을 열고 "연결" 버튼을 돌려준다. */
  async function openLinkForm(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole("button", { name: "진주회관 메뉴 연결" }));
    await screen.findByRole("heading", { name: "메뉴 연결" });
    return screen.getByRole("button", { name: "연결" });
  }

  it("등록된 메뉴가 없으면 초점은 받되 '먼저 메뉴를 등록하라'가 함께 읽힌다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    const link = await openLinkForm(user);
    await screen.findByText(/등록된 메뉴가 없습니다/);

    expect(link).toHaveAttribute("aria-disabled", "true");
    link.focus();
    expect(link).toHaveFocus();
    // 이 안내가 버튼에 묶여 있지 않으면, 화면을 못 보는 사용자에게는 "사용 불가"까지만
    // 읽히고 무엇을 해야 하는지는 끝내 전달되지 않는다.
    expect(link).toHaveAccessibleDescription(/먼저 메뉴를 등록/);
  });

  it("등록된 메뉴가 없을 때 눌러도 연결 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    const link = await openLinkForm(user);
    await screen.findByText(/등록된 메뉴가 없습니다/);
    await user.click(link);

    expect(createMenuRestaurantMock).not.toHaveBeenCalled();
  });

  it("메뉴를 고르지 않고 누르면 요청이 나가지 않고 초점이 메뉴 칩으로 간다", async () => {
    fetchMenusMock.mockResolvedValue({ menus: [KIMCHI], nextCursor: null, hasNext: false });
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    const link = await openLinkForm(user);
    const chip = await screen.findByRole("button", { name: "김치찌개" });
    await user.click(link);

    expect(createMenuRestaurantMock).not.toHaveBeenCalled();
    expect(await screen.findByRole("alert")).toHaveTextContent("연결할 메뉴를 먼저 선택해주세요.");
    // 고를 메뉴가 있는 경우에는 갈 곳이 있다 — 안내만 읽어주고 초점을 버튼에 두면
    // 그 칩까지 가는 길은 사용자가 직접 찾아야 한다.
    expect(chip).toHaveFocus();
    expect(link).toHaveAccessibleDescription("연결할 메뉴를 먼저 선택해주세요.");
  });

  it("메뉴를 고르면 잠금이 풀리고 연결할 수 있다", async () => {
    fetchMenusMock.mockResolvedValue({ menus: [KIMCHI], nextCursor: null, hasNext: false });
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    const link = await openLinkForm(user);
    await user.click(await screen.findByRole("button", { name: "김치찌개" }));

    expect(link).not.toHaveAttribute("aria-disabled");
    // 사유가 사라진 뒤에도 참조가 남아 있으면 스크린리더가 빈 설명을 읽는다.
    expect(link).not.toHaveAttribute("aria-describedby");

    await user.click(link);
    await waitFor(() => expect(createMenuRestaurantMock).toHaveBeenCalledTimes(1));
  });

  it("연결 중에도 초점을 지키고 연타되지 않는다", async () => {
    fetchMenusMock.mockResolvedValue({ menus: [KIMCHI], nextCursor: null, hasNext: false });
    createMenuRestaurantMock.mockReturnValue(pendingForever());
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    const link = await openLinkForm(user);
    await user.click(await screen.findByRole("button", { name: "김치찌개" }));
    await user.click(link);

    const busyButton = await screen.findByRole("button", { name: "연결 중…" });
    expect(busyButton).toHaveAttribute("aria-busy", "true");
    expect(busyButton).toHaveFocus();

    await user.click(busyButton);
    expect(createMenuRestaurantMock).toHaveBeenCalledTimes(1);
  });
});

describe("연결할 메뉴 찾기", () => {
  const KIMCHI = { id: 27, name: "김치찌개", weight: 3, isExcluded: false, categories: [], tags: [] };
  const OLD_MENU = { ...KIMCHI, id: 7, name: "된장찌개" };
  const page = (menus: typeof KIMCHI[], nextCursor: number | null = null) => ({
    menus, nextCursor, hasNext: nextCursor !== null,
  });

  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
    fetchRestaurantsMock.mockResolvedValue([JINJU]);
    fetchRestaurantMock.mockResolvedValue(jinjuDetail);
  });

  async function openPicker(user: ReturnType<typeof userEvent.setup>) {
    renderWithProviders(<RestaurantsPage />);
    await user.click(await screen.findByRole("button", { name: "진주회관 메뉴 연결" }));
    return screen.findByRole("textbox", { name: "연결할 메뉴 검색" });
  }

  it("다음 페이지의 오래된 메뉴를 골라 별점과 메모를 함께 연결한다", async () => {
    const firstPage = Array.from({ length: 20 }, (_, i) => ({ ...KIMCHI, id: 100 - i, name: `메뉴 ${i}` }));
    fetchMenusMock.mockResolvedValueOnce(page(firstPage, 81)).mockResolvedValueOnce(page([OLD_MENU]));
    const user = userEvent.setup();
    await openPicker(user);
    await user.click(await screen.findByRole("button", { name: "메뉴 더 보기" }));
    await user.click(await screen.findByRole("button", { name: "된장찌개" }));
    expect(fetchMenusMock).toHaveBeenLastCalledWith(81, 20, undefined);
    expect(screen.getByRole("button", { name: "메뉴 0" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "메뉴 더 보기" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "별점 5" }));
    await user.type(screen.getByRole("textbox", { name: "메모" }), "  다시 갈 집  ");
    await user.click(screen.getByRole("button", { name: "연결" }));
    await waitFor(() => expect(createMenuRestaurantMock).toHaveBeenCalledWith(7, {
      restaurantId: 1, rating: 5, memo: "다시 갈 집",
    }));
  });

  it("검색 결과의 다음 페이지에도 같은 검색어를 전달한다", async () => {
    fetchMenusMock.mockImplementation(async (cursor, _size, keyword) => {
      if (!keyword) return page([KIMCHI]);
      return cursor == null ? page([KIMCHI], 27) : page([OLD_MENU]);
    });
    const user = userEvent.setup();
    const input = await openPicker(user);
    await user.type(input, "  찌개  ");
    // 입력만으로 조회하지 않는다.
    expect(fetchMenusMock).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "메뉴 검색" }));
    await user.click(await screen.findByRole("button", { name: "메뉴 더 보기" }));
    expect(await screen.findByRole("button", { name: "된장찌개" })).toBeInTheDocument();
    expect(fetchMenusMock).toHaveBeenLastCalledWith(27, 20, "찌개");
  });

  it("검색창의 Enter는 검색만 하고 이전 선택을 해제한다", async () => {
    fetchMenusMock.mockResolvedValue(page([KIMCHI]));
    const user = userEvent.setup();
    const input = await openPicker(user);
    await user.click(await screen.findByRole("button", { name: "김치찌개" }));
    await user.type(input, "찌개{Enter}");
    await waitFor(() => expect(fetchMenusMock).toHaveBeenLastCalledWith(undefined, 20, "찌개"));
    expect(createMenuRestaurantMock).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "연결" })).toHaveAttribute("aria-disabled", "true");
    expect(await screen.findByRole("button", { name: "김치찌개" })).toHaveAttribute("aria-pressed", "false");
  });

  it("검색 결과 없음과 메뉴 미등록을 구분하고 초기화하면 전체 목록으로 돌아간다", async () => {
    fetchMenusMock.mockImplementation(async (_cursor, _size, keyword) => page(keyword ? [] : [KIMCHI]));
    const user = userEvent.setup();
    const input = await openPicker(user);
    await user.type(input, "없는메뉴{Enter}");
    const empty = await screen.findByText(/검색 결과가 없습니다/);
    expect(empty).toHaveAttribute("role", "status");
    expect(empty).toHaveTextContent("검색 결과가 없습니다.");
    expect(screen.queryByText(/먼저 메뉴를 등록/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "연결" })).toHaveAccessibleDescription(/검색을 초기화/);
    await user.click(screen.getByRole("button", { name: "검색 초기화" }));
    expect(input).toHaveValue("");
    expect(await screen.findByRole("button", { name: "김치찌개" })).toBeInTheDocument();
  });

  it("늦게 끝난 이전 검색이 새 검색 결과를 덮지 않는다", async () => {
    let finishOldSearch!: (value: ReturnType<typeof page>) => void;
    fetchMenusMock.mockImplementation((_cursor, _size, keyword) => {
      if (keyword === "김치") return new Promise((resolve) => { finishOldSearch = resolve; });
      return Promise.resolve(page(keyword === "된장" ? [OLD_MENU] : [KIMCHI]));
    });
    const user = userEvent.setup();
    const input = await openPicker(user);
    await user.type(input, "김치{Enter}");
    await waitFor(() => expect(finishOldSearch).toBeDefined());
    await user.clear(input);
    await user.type(input, "된장{Enter}");
    await screen.findByRole("button", { name: "된장찌개" });
    await act(async () => { finishOldSearch(page([KIMCHI])); });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "된장찌개" })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "김치찌개" })).not.toBeInTheDocument();
    });
  });

  it("더 보기 실패 후에도 기존 선택을 유지하고 같은 커서로 재시도한다", async () => {
    fetchMenusMock.mockResolvedValueOnce(page([KIMCHI], 27))
      .mockRejectedValueOnce(new Error("잠시 후 다시 시도해주세요"))
      .mockResolvedValueOnce(page([OLD_MENU]));
    const user = userEvent.setup();
    await openPicker(user);
    await user.click(await screen.findByRole("button", { name: "김치찌개" }));
    await user.click(screen.getByRole("button", { name: "메뉴 더 보기" }));
    await screen.findByRole("alert");
    expect(screen.getByRole("button", { name: "김치찌개" })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "메뉴 더 보기" }));
    await screen.findByRole("button", { name: "된장찌개" });
    expect(fetchMenusMock.mock.calls.slice(1)).toEqual([[27, 20, undefined], [27, 20, undefined]]);
  });
});

/**
 * 카드 안의 폼 제목은 목록 제목보다 한 단계 아래여야 문서 개요에서도 소속이 드러난다.
 */
describe("카드 안 폼의 제목 레벨", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
    fetchRestaurantsMock.mockResolvedValue([JINJU]);
    fetchRestaurantMock.mockResolvedValue(jinjuDetail);
  });

  it("수정 폼 제목은 목록 제목보다 한 단계 아래다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    await user.click(await screen.findByRole("button", { name: "진주회관 수정" }));

    expect(
      await screen.findByRole("heading", { level: 3, name: "식당 수정" }),
    ).toBeInTheDocument();
    // 목록 제목은 h2 그대로여야 "폼이 목록 안에 있다"는 관계가 성립한다.
    expect(screen.getByRole("heading", { level: 2, name: "저장한 식당" })).toBeInTheDocument();
  });

  it("메뉴 연결 폼 제목도 목록 제목보다 한 단계 아래다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RestaurantsPage />);

    await user.click(await screen.findByRole("button", { name: "진주회관 메뉴 연결" }));

    expect(
      await screen.findByRole("heading", { level: 3, name: "메뉴 연결" }),
    ).toBeInTheDocument();
  });
});

/**
 * 장소 검색 {@code <section>}에 접근 가능한 이름이 없었다.
 *
 * <p>이름 없는 {@code <section>}은 랜드마크로 노출되지 않아 실질적으로 {@code <div>}다.
 * 검색 결과가 15건 늘어선 상태에서 아래 "저장한 식당" 목록으로 넘어가려면 결과 카드의
 * 버튼을 전부 Tab으로 지나가는 수밖에 없었다.
 */
describe("장소 검색 섹션 랜드마크", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
    fetchRestaurantsMock.mockResolvedValue([]);
  });

  it("제목으로 이름이 붙어 랜드마크로 잡힌다", async () => {
    renderWithProviders(<RestaurantsPage />);

    const section = await screen.findByRole("region", { name: "장소 검색으로 식당 추가" });

    // 이름만 맞고 엉뚱한 요소에 붙었으면 안 된다 — 실제 검색 칸을 감싸고 있어야 한다.
    expect(within(section).getByRole("textbox", { name: "장소 검색어" })).toBeInTheDocument();
  });
});

describe("수정할 때 상세 조회", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
    fetchRestaurantsMock.mockResolvedValue([JINJU]);
  });

  it("수정을 눌렀을 때만 조회하고 대기 중에도 취소할 수 있다", async () => {
    const user = userEvent.setup();
    fetchRestaurantMock.mockReturnValue(pendingForever<typeof jinjuDetail>());
    renderWithProviders(<RestaurantsPage />);
    const edit = await screen.findByRole("button", { name: "진주회관 수정" });
    expect(fetchRestaurantMock).not.toHaveBeenCalled();
    await user.click(edit);
    expect(fetchRestaurantMock).toHaveBeenCalledTimes(1);
    expect(screen.getByText("수정할 식당 정보를 불러오는 중…")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "수정 취소" }));
    expect(screen.getByRole("button", { name: "진주회관 수정" })).toHaveFocus();
  });

  it("조회 도중 취소하면 늦게 도착한 응답이 수정 폼을 열지 않는다", async () => {
    const user = userEvent.setup();
    let arriveDetail!: (detail: typeof jinjuDetail) => void;
    const loading = new Promise<typeof jinjuDetail>((resolve) => { arriveDetail = resolve; });
    fetchRestaurantMock.mockReturnValue(loading);
    renderWithProviders(<RestaurantsPage />);
    await user.click(await screen.findByRole("button", { name: "진주회관 수정" }));
    await user.click(screen.getByRole("button", { name: "수정 취소" }));
    await act(async () => { arriveDetail(jinjuDetail); await loading; });
    expect(screen.queryByRole("heading", { name: "식당 수정" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "진주회관 수정" })).toHaveFocus();
  });

  it("조회 실패를 알리고 취소와 재시도를 제공한다", async () => {
    const user = userEvent.setup();
    fetchRestaurantMock.mockRejectedValue(new Error("상세를 못 가져왔다"));
    renderWithProviders(<RestaurantsPage />);
    await user.click(await screen.findByRole("button", { name: "진주회관 수정" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("상세를 못 가져왔다");
    expect(screen.getByRole("button", { name: "진주회관 상세 정보 다시 불러오기" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "수정 취소" }));
    expect(screen.getByRole("button", { name: "진주회관 수정" })).toHaveFocus();
  });

  it("재시도가 성공하면 상세 정보로 폼을 채운다", async () => {
    const user = userEvent.setup();
    fetchRestaurantMock.mockRejectedValueOnce(new Error("상세를 못 가져왔다")).mockResolvedValue(jinjuDetail);
    renderWithProviders(<RestaurantsPage />);
    await user.click(await screen.findByRole("button", { name: "진주회관 수정" }));
    await user.click(await screen.findByRole("button", { name: "진주회관 상세 정보 다시 불러오기" }));
    expect(await screen.findByRole("heading", { name: "식당 수정" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "식당 이름" })).toHaveValue("진주회관");
    expect(fetchRestaurantMock).toHaveBeenCalledTimes(2);
  });
});

/**
 * `naverUrl`은 카드에서 그대로 `<a href>`가 된다. 정상 경로에서는 카카오 장소 검색이 준
 * https만 들어가지만, 값 자체는 사용자가 정하는 필드다 — API를 직접 호출해 `javascript:`를
 * 저장하면 클릭 한 번에 우리 오리진에서 스크립트가 돈다(self-XSS). 지금은 자기 데이터만
 * 자기가 보므로 피해가 자신에게 갇히지만, 공유·추천처럼 남의 식당이 내 화면에 그려지는
 * 기능이 하나 붙는 순간 저장형 XSS가 된다.
 */
describe("지도 보기 링크", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
    fetchRestaurantsMock.mockResolvedValue([JINJU]);
  });

  it("https 주소면 링크를 건다", async () => {
    fetchRestaurantsMock.mockResolvedValue([{ ...JINJU, naverUrl: "https://place.map.kakao.com/12345" }]);

    renderWithProviders(<RestaurantsPage />);

    const link = await screen.findByRole("link", { name: "진주회관 지도 보기 (새 창)" });
    expect(link).toHaveAttribute("href", "https://place.map.kakao.com/12345");
  });

  it("javascript: 주소는 링크로 걸지 않는다", async () => {
    fetchRestaurantsMock.mockResolvedValue([{ ...JINJU, naverUrl: "javascript:alert(document.cookie)" }]);

    renderWithProviders(<RestaurantsPage />);

    // 카드 자체는 그대로 떠야 한다 — 지도 보기는 부가 정보라 없어도 나머지를 쓸 수 있다.
    expect(await screen.findByText("진주회관")).toBeInTheDocument();
    expect(fetchRestaurantMock).not.toHaveBeenCalled();
    expect(screen.queryByRole("link", { name: /지도 보기/ })).not.toBeInTheDocument();
    expect(document.querySelector('a[href^="javascript:"]')).toBeNull();
  });
});

it("저장한 식당 여러 개를 표시할 때 카드별 상세 API를 호출하지 않는다", async () => {
  fetchRestaurantsMock.mockResolvedValue([1, 2, 3].map((id) => ({
    id, name: `목록 식당 ${id}`, address: "서울", latitude: 37.5, longitude: 127,
  })));
  renderWithProviders(<RestaurantsPage />);
  await screen.findByText("목록 식당 3");
  expect(fetchRestaurantsMock).toHaveBeenCalledTimes(1);
  expect(fetchRestaurantMock).not.toHaveBeenCalled();
});
it("수정 화면을 다시 열 때 캐시 대신 최신 상세와 버전을 기다린다", async () => {
  const user = userEvent.setup();
  let finish!: (detail: RestaurantDetail) => void;
  const fresh = new Promise<RestaurantDetail>((resolve) => { finish = resolve; });
  fetchRestaurantsMock.mockResolvedValue([JINJU]);
  fetchRestaurantMock.mockResolvedValueOnce({ ...jinjuDetail, version: 3 }).mockReturnValueOnce(fresh);
  renderWithProviders(<RestaurantsPage />);
  await user.click(await screen.findByRole("button", { name: "진주회관 수정" }));
  await screen.findByRole("textbox", { name: "식당 이름" });
  await user.click(screen.getByRole("button", { name: "취소" }));
  await user.click(screen.getByRole("button", { name: "진주회관 수정" }));
  expect(screen.getByText("수정할 식당 정보를 불러오는 중…")).toBeInTheDocument();
  expect(screen.queryByRole("textbox", { name: "식당 이름" })).not.toBeInTheDocument();
  await act(async () => { finish({ ...jinjuDetail, version: 7, phone: "02-7777" }); await fresh; });
  expect(await screen.findByRole("textbox", { name: "전화" })).toHaveValue("02-7777");
  await user.click(screen.getByRole("button", { name: "저장" }));
  await waitFor(() => expect(updateRestaurantMock).toHaveBeenCalledWith(1, expect.objectContaining({ version: 7 })));
  expect(fetchRestaurantMock).toHaveBeenCalledTimes(2);
});

it("창 포커스나 재연결로 편집 초안과 시작 버전이 바뀌지 않는다", async () => {
  const user = userEvent.setup();
  fetchRestaurantsMock.mockResolvedValue([JINJU]);
  fetchRestaurantMock.mockResolvedValue({ ...jinjuDetail, version: 4 });
  renderWithProviders(<RestaurantsPage />);
  await user.click(await screen.findByRole("button", { name: "진주회관 수정" }));
  const name = await screen.findByRole("textbox", { name: "식당 이름" });
  await user.clear(name);
  await user.type(name, "작성 중인 이름");
  await act(async () => {
    focusManager.setFocused(false);
    focusManager.setFocused(true);
    onlineManager.setOnline(false);
    onlineManager.setOnline(true);
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
  expect(name).toHaveValue("작성 중인 이름");
  expect(fetchRestaurantMock).toHaveBeenCalledTimes(1);
  await user.click(screen.getByRole("button", { name: "저장" }));
  await waitFor(() => expect(updateRestaurantMock).toHaveBeenCalledWith(1, expect.objectContaining({ name: "작성 중인 이름", version: 4 })));
  focusManager.setFocused(undefined);
});

it("수정 충돌에서 초안을 유지하고 상세를 자동 재조회하지 않는다", async () => {
  const user = userEvent.setup();
  fetchRestaurantsMock.mockResolvedValue([JINJU]);
  fetchRestaurantMock.mockResolvedValue({ ...jinjuDetail, version: 4 });
  updateRestaurantMock.mockRejectedValue(new AxiosError("conflict", undefined, undefined, undefined, {
    status: 409, data: { errorCode: "CONCURRENT_MODIFICATION", message: "다른 요청에서 수정했습니다." },
    statusText: "Conflict", headers: {}, config: {} as never,
  }));
  renderWithProviders(<RestaurantsPage />);
  await user.click(await screen.findByRole("button", { name: "진주회관 수정" }));
  const name = await screen.findByRole("textbox", { name: "식당 이름" });
  await user.clear(name);
  await user.type(name, "보존할 초안");
  await user.click(screen.getByRole("button", { name: "저장" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("다른 요청에서 수정했습니다.");
  expect(name).toHaveValue("보존할 초안");
  expect(fetchRestaurantMock).toHaveBeenCalledTimes(1);
  expect(screen.getByText(/작성한 내용은 유지했습니다/)).toBeInTheDocument();
});

it("늦게 도착한 상세 응답이 사용자가 옮긴 초점을 가져가지 않는다", async () => {
  const user = userEvent.setup();
  let finish!: (detail: typeof jinjuDetail) => void;
  const fresh = new Promise<typeof jinjuDetail>((resolve) => { finish = resolve; });
  fetchRestaurantsMock.mockResolvedValue([JINJU]);
  fetchRestaurantMock.mockReturnValue(fresh);
  renderWithProviders(<RestaurantsPage />);
  await user.click(await screen.findByRole("button", { name: "진주회관 수정" }));
  const search = screen.getByRole("searchbox", { name: "저장한 식당 검색" });
  await user.click(search);
  await act(async () => { finish(jinjuDetail); await fresh; });
  await screen.findByRole("textbox", { name: "식당 이름" });
  expect(search).toHaveFocus();
});
