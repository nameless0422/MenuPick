import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import RestaurantsPage from "./RestaurantsPage";
import { fetchRestaurant, fetchRestaurants, updateRestaurant } from "../api/restaurants";
import { resetKakaoSdkForTest } from "../maps/kakaoSdk";
import { searchPlacesByKeyword } from "../api/places";
import { fetchMenus } from "../api/menus";
import { createMenuRestaurant } from "../api/menuRestaurants";

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
const updateRestaurantMock = vi.mocked(updateRestaurant);
const fetchMenusMock = vi.mocked(fetchMenus);
const createMenuRestaurantMock = vi.mocked(createMenuRestaurant);

/** 끝나지 않는 요청. "요청이 나가 있는 동안"의 화면을 붙잡아 두려면 이게 필요하다. */
function pendingForever<T>() {
  return new Promise<T>(() => {});
}

beforeEach(() => {
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
};

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
 * <p>이 폼에는 텍스트 입력이 없어 Enter로 제출되는 경로가 지금은 없다 — 그래도 조기 반환은
 * form onSubmit에 둔다(입력 한 칸만 늘어도 클릭 핸들러의 방어는 뚫린다).
 */
describe("메뉴 연결 버튼", () => {
  const KIMCHI = { id: 7, name: "김치찌개", weight: 3, isExcluded: false, categories: [], tags: [] };

  beforeEach(() => {
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");
    fetchRestaurantsMock.mockResolvedValue([JINJU]);
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
