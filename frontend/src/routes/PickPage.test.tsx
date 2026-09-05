import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import PickPage from "./PickPage";
import { requestPick } from "../api/pick";
import { searchTags } from "../api/tags";
import { resetKakaoSdkForTest } from "../maps/kakaoSdk";

vi.mock("../api/pick", () => ({ requestPick: vi.fn() }));
vi.mock("../api/tags", () => ({ searchTags: vi.fn().mockResolvedValue([]) }));

const requestPickMock = vi.mocked(requestPick);
const searchTagsMock = vi.mocked(searchTags);
const HONBAP = { id: 7, name: "혼밥", createdAt: "2026-01-01T00:00:00" };

/**
 * 위치 요청을 붙잡아 두는 스텁.
 *
 * <p>실제 {@code getCurrentPosition}은 취소할 수단이 없고 최대 10초까지 매달린다. 사용자가
 * 그 사이에 필터를 끄거나 다시 켜는 것이 이 파일이 검증하는 상황이라, 콜백을 언제 부를지
 * 테스트가 직접 정할 수 있어야 한다.
 */
let pendingRequests: {
  success: PositionCallback;
  error: PositionErrorCallback | null;
}[] = [];

function resolveRequest(index: number, latitude: number, longitude: number) {
  const request = pendingRequests[index];
  act(() => {
    request.success({ coords: { latitude, longitude } } as GeolocationPosition);
  });
}

function failRequest(index: number) {
  const request = pendingRequests[index];
  act(() => {
    request.error?.({ code: 1 } as GeolocationPositionError);
  });
}

beforeEach(() => {
  pendingRequests = [];
  resetKakaoSdkForTest();
  delete window.kakao;
  requestPickMock.mockReset();
  // 태그 제안을 쓰는 테스트가 뒤 테스트로 새지 않게 매번 빈 목록으로 되돌린다.
  searchTagsMock.mockResolvedValue([]);
  // 이 파일이 보는 것은 픽 요청에 무엇이 실려 나가는지이므로 응답은 최소 형태로 고정한다.
  requestPickMock.mockResolvedValue({
    historyId: 1,
    menu: {
      id: 1,
      name: "김치찌개",
      memo: null,
      categories: [],
      tags: [],
      weight: 1,
      isExcluded: false,
      createdAt: "2026-01-01T00:00:00",
      updatedAt: "2026-01-01T00:00:00",
      version: 0,
    },
    restaurants: [],
    reasons: ["선호도 1/5를 반영했어요", "최근 3일간 추천되지 않았어요"],
  });

  Object.defineProperty(navigator, "geolocation", {
    configurable: true,
    value: {
      getCurrentPosition: (success: PositionCallback, error?: PositionErrorCallback) => {
        pendingRequests.push({ success, error: error ?? null });
      },
    },
  });
});

const distanceToggle = () => screen.getByRole("checkbox", { name: /내 위치 기준으로/ });
const spinButton = () => screen.getByRole("button", { name: /오늘의 메뉴 뽑기/ });

it("픽 결과에 서버가 계산한 추천 이유를 표시한다", async () => {
  const user = userEvent.setup();
  renderWithProviders(<PickPage />);

  await user.click(spinButton());

  expect(await screen.findByText("이 메뉴를 고른 이유", {}, { timeout: 3000 })).toBeInTheDocument();
  const reasons = screen.getByLabelText("추천 이유");
  expect(within(reasons).getByText("선호도 1/5를 반영했어요")).toBeInTheDocument();
  expect(within(reasons).getByText("최근 3일간 추천되지 않았어요")).toBeInTheDocument();
});

describe("PickPage 거리 필터 — 위치 요청 취소", () => {
  it("취소한 뒤 위치가 도착해도 필터가 스스로 켜지지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(distanceToggle());
    await waitFor(() => expect(pendingRequests).toHaveLength(1));

    // 사용자가 기다리다 껐다
    await user.click(distanceToggle());
    expect(distanceToggle()).not.toBeChecked();

    // 그 뒤에야 위치가 도착한다
    resolveRequest(0, 37.5, 127.0);

    expect(distanceToggle()).not.toBeChecked();
  });

  it("취소했으면 픽 요청에 좌표가 실리지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(distanceToggle());
    await waitFor(() => expect(pendingRequests).toHaveLength(1));
    await user.click(distanceToggle());
    resolveRequest(0, 37.5, 127.0);

    await user.click(spinButton());

    await waitFor(() => expect(requestPickMock).toHaveBeenCalledTimes(1));
    const sent = requestPickMock.mock.calls[0][0];
    expect(sent.latitude).toBeUndefined();
    expect(sent.longitude).toBeUndefined();
    expect(sent.maxDistance).toBeUndefined();
  });

  it("껐다 켤 때 먼저 보낸 요청의 오래된 좌표가 최신 좌표를 덮어쓰지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(distanceToggle()); // 요청 A
    await waitFor(() => expect(pendingRequests).toHaveLength(1));
    await user.click(distanceToggle()); // 껐다
    await user.click(distanceToggle()); // 요청 B
    await waitFor(() => expect(pendingRequests).toHaveLength(2));

    // A가 B보다 늦게 도착하는 순서
    resolveRequest(1, 37.5665, 126.978);
    resolveRequest(0, 11.11, 22.22);

    await user.click(spinButton());

    await waitFor(() => expect(requestPickMock).toHaveBeenCalledTimes(1));
    const sent = requestPickMock.mock.calls[0][0];
    expect(sent.latitude).toBe(37.5665);
    expect(sent.longitude).toBe(126.978);
  });

  it("취소한 뒤 도착한 실패는 권한 오류 안내를 띄우지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(distanceToggle());
    await waitFor(() => expect(pendingRequests).toHaveLength(1));
    await user.click(distanceToggle());

    failRequest(0);

    expect(screen.queryByText(/위치를 가져오지 못해/)).not.toBeInTheDocument();
  });

  it("정상 흐름에서는 좌표와 거리 상한이 픽 요청에 실린다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(distanceToggle());
    await waitFor(() => expect(pendingRequests).toHaveLength(1));
    resolveRequest(0, 37.5665, 126.978);

    await user.click(spinButton());

    await waitFor(() => expect(requestPickMock).toHaveBeenCalledTimes(1));
    const sent = requestPickMock.mock.calls[0][0];
    expect(sent.latitude).toBe(37.5665);
    expect(sent.longitude).toBe(126.978);
    expect(sent.maxDistance).toBe(500);
  });
});

/**
 * 픽 결과에 지도를 얹었지만, 운영 서버에는 아직 VITE_KAKAO_JS_KEY가 없다.
 * 지도가 못 뜨는 상태에서 결과 카드가 함께 죽으면 앱의 핵심 기능이 사라진다.
 */
describe("PickPage 결과 지도", () => {
  it("지도 키가 없어도 추천 식당 목록은 그대로 보인다", async () => {
    requestPickMock.mockResolvedValue({
      historyId: 1,
      menu: {
        id: 1,
        name: "김치찌개",
        memo: null,
        categories: [],
        tags: [],
        weight: 1,
        isExcluded: false,
        createdAt: "2026-01-01T00:00:00",
        updatedAt: "2026-01-01T00:00:00",
        version: 0,
      },
      restaurants: [
        {
          id: 10,
          name: "진주회관",
          address: "서울시 중구",
          latitude: 37.5665,
          longitude: 126.978,
          distance: 120,
        },
      ],
    });
    vi.stubEnv("VITE_KAKAO_JS_KEY", "");

    const user = userEvent.setup();
    renderWithProviders(<PickPage />);
    await user.click(spinButton());

    // 슬롯 연출(SPIN_MS)이 끝나야 결과가 공개된다
    expect(await screen.findByText("진주회관", undefined, { timeout: 3000 })).toBeInTheDocument();
    // 지도 컴포넌트는 결과 카드와 함께 마운트되므로 키 판정은 한 틱 뒤에 반영된다
    expect(await screen.findByText(/지도 키가 설정되지 않아/)).toBeInTheDocument();
    expect(screen.getByText("120m")).toBeInTheDocument();
  });
});

/**
 * TagFilter는 "포함 태그"와 "제외 태그"로 <b>두 번</b> 렌더된다. legend는 fieldset에만 붙어
 * 있어 입력의 이름 계산에 들어오지 않으므로, 입력마다 이름이 없으면 음성 제어로 어느 쪽을
 * 지목했는지 알 수 없고 스크린리더에도 똑같은 칸 두 개로만 보인다.
 */
describe("필터 입력의 이름", () => {
  it("포함/제외 태그 검색 입력이 서로 구분되는 이름을 갖는다", async () => {
    renderWithProviders(<PickPage />);

    expect(await screen.findByRole("textbox", { name: "포함 태그 검색" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "제외 태그 검색" })).toBeInTheDocument();
  });

  it("카테고리 직접 입력에 이름이 있다", async () => {
    renderWithProviders(<PickPage />);

    expect(await screen.findByRole("textbox", { name: "직접 입력한 카테고리" })).toBeInTheDocument();
  });
});

/**
 * 픽은 초점을 옮기지 않고 화면 일부만 바꾼다 — 통지가 없으면 Enter를 친 뒤 무슨 일이
 * 일어났는지 알 수 없다. 특히 뽑는 동안은 최소 1.2초가 <b>완전한 무음</b>이었다:
 * 돌아가는 이모지는 aria-hidden이라 들리는 것이 하나도 없다.
 */
describe("픽 진행·결과 통지", () => {
  it("뽑는 동안 무음으로 두지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(spinButton());

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("메뉴를 뽑는 중…"));
  });

  it("추천 식당이 몇 곳인지 알린다", async () => {
    const user = userEvent.setup();
    requestPickMock.mockResolvedValue({
      historyId: 1,
      menu: {
        id: 1,
        name: "김치찌개",
        memo: null,
        categories: [],
        tags: [],
        weight: 1,
        isExcluded: false,
        createdAt: "2026-01-01T00:00:00",
        updatedAt: "2026-01-01T00:00:00",
        version: 0,
      },
      restaurants: [
        { id: 1, name: "진주회관", address: "서울시 중구", latitude: 37.5665, longitude: 126.978, distance: null },
        { id: 2, name: "을지면옥", address: "서울시 중구", latitude: 37.566, longitude: 126.99, distance: null },
      ],
    } as never);
    renderWithProviders(<PickPage />);

    await user.click(spinButton());

    // 지도를 못 보는 사람에게 이 목록은 유일한 경로다 — 건수부터 알아야 한다.
    // 슬롯 연출이 최소 1.2초라 기본 대기(1초)로는 결과가 아직 안 나온다.
    expect(await screen.findByText("추천 식당 2곳", {}, { timeout: 3000 })).toBeInTheDocument();
  });

  it("위치 권한 실패를 통지한다 — 체크박스가 스스로 꺼지는 이유다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(distanceToggle());
    await waitFor(() => expect(pendingRequests).toHaveLength(1));
    failRequest(0);

    // 최대 10초 뒤에 비동기로 나타난다. role이 없으면 "이유 없이 되돌아간 것"으로만 보인다.
    expect(await screen.findByRole("alert")).toHaveTextContent(/위치를 가져오지 못해/);
  });
});

/**
 * disabled인 요소는 초점을 받지 못한다 — 누르는 순간 초점이 그 버튼에서 {@code <body>}로
 * 떨어지고, 요청이 끝나 다시 활성화돼도 돌아오지 않는다. 픽 버튼은 여기에 더해 돌리는 동안
 * 유일한 자식이 aria-hidden이라 <b>접근 가능한 이름이 빈 문자열</b>이 됐다. 즉 Enter를 친
 * 뒤 1.2초 동안 버튼은 이름도 없고 초점도 없는 상태였고, 다시 돌리려면 페이지 맨 위에서
 * Tab을 눌러 내려와야 했다.
 */
describe("픽 버튼 — disabled 대신 aria-busy", () => {
  it("돌리는 동안에도 버튼 이름이 그대로 남는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(spinButton());

    // 이름은 "무엇을 하는 버튼인가"이므로 진행 상태에 따라 바뀌면 안 된다.
    const button = await screen.findByRole("button", { name: "오늘의 메뉴 뽑기" });
    expect(button).toHaveAttribute("aria-busy", "true");
    // disabled면 초점을 잃는다. 진행 중임은 aria-disabled/aria-busy로만 알린다.
    expect(button).toBeEnabled();
    expect(button).toHaveAttribute("aria-disabled", "true");
  });

  it("돌리는 동안 눌러도 픽 요청이 겹쳐 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    // aria-disabled는 표시일 뿐 클릭을 막지 않는다 — 핸들러의 조기 반환이 실제 방어선이다.
    await user.click(spinButton());
    await user.click(spinButton());

    expect(requestPickMock).toHaveBeenCalledTimes(1);
  });

  it("결과 카드의 다시 돌리기를 누르면 초점이 픽 버튼으로 옮겨간다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(spinButton());
    // 슬롯 연출이 최소 1.2초라 기본 대기(1초)로는 결과가 아직 안 나온다.
    const retry = await screen.findByRole("button", { name: /다시 돌리기/ }, { timeout: 3000 });
    await user.click(retry);

    // 다시 돌리기는 결과 카드 안에 있어 누르는 순간 카드째 사라진다 — 갈 곳을 주지 않으면
    // 초점이 <body>로 떨어진다.
    expect(spinButton()).toHaveFocus();
  });
});

/**
 * 칩은 누르면 다른 행으로 옮겨가며 언마운트된다. 매번 초점이 {@code <body>}로 떨어져
 * 태그를 연달아 고르는 것이 사실상 불가능했다.
 */
describe("필터 칩 — 눌러도 갈 곳이 남는다", () => {
  it("직접 입력한 카테고리는 해제해도 목록에서 사라지지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.type(await screen.findByRole("textbox", { name: "직접 입력한 카테고리" }), "떡볶이");
    await user.click(screen.getByRole("button", { name: "추가" }));

    const chip = await screen.findByRole("button", { name: "떡볶이" });
    expect(chip).toHaveAttribute("aria-pressed", "true");

    await user.click(chip);

    // 해제하면 selected에서 빠지는데, 그것만으로 목록을 만들면 칩이 영영 사라져
    // 다시 쓰려면 처음부터 타이핑해야 했다.
    const stillThere = screen.getByRole("button", { name: "떡볶이" });
    expect(stillThere).toHaveAttribute("aria-pressed", "false");
    expect(stillThere).toHaveFocus();
  });

  // 입력이 비는 순간 "추가"가 disabled가 되어, 방금 누른 그 버튼이 초점을 받을 수 없게 된다.
  it("카테고리를 추가하면 초점이 입력으로 돌아온다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    const input = await screen.findByRole("textbox", { name: "직접 입력한 카테고리" });
    await user.type(input, "떡볶이");
    await user.click(screen.getByRole("button", { name: "추가" }));

    expect(input).toHaveFocus();
  });

  it("태그를 고르면 초점이 그 태그 검색 입력으로 간다", async () => {
    searchTagsMock.mockResolvedValue([HONBAP]);
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    // 포함/제외 두 필터가 같은 키로 조회하므로 제안 칩도 두 벌 나온다 — 앞쪽이 포함 태그다.
    const suggestions = await screen.findAllByRole("button", { name: "혼밥 태그 추가" });
    await user.click(suggestions[0]);

    // 고른 칩은 제안 행에서 사라져 돌아갈 자리가 없다. 다음 행동이 시작되는 곳으로 모은다.
    expect(screen.getByRole("textbox", { name: "포함 태그 검색" })).toHaveFocus();
  });

  it("선택한 태그를 해제해도 초점이 검색 입력에 남는다", async () => {
    searchTagsMock.mockResolvedValue([HONBAP]);
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click((await screen.findAllByRole("button", { name: "혼밥 태그 추가" }))[0]);
    await user.click(screen.getByRole("button", { name: "혼밥 태그 선택 해제" }));

    expect(screen.getByRole("textbox", { name: "포함 태그 검색" })).toHaveFocus();
  });
});

/**
 * 슬롯 연출은 80ms(초당 12.5회) 간격으로 이모지를 최소 1.2초 교체한다. 2.2.2(5초 초과)
 * 대상도 아니고 섬광도 아니지만, 전정기관이 민감한 사용자에게는 불필요한 부하다.
 * 이 앱의 유일한 모션이라 CSS에는 대응할 것이 없고 이 인터벌만 막으면 된다.
 */
describe("모션 최소화", () => {
  it("prefers-reduced-motion이면 이모지를 바꾸지 않는다", async () => {
    const original = window.matchMedia;
    window.matchMedia = ((query: string) => ({
      matches: query.includes("prefers-reduced-motion"),
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    })) as typeof window.matchMedia;

    try {
      const user = userEvent.setup();
      renderWithProviders(<PickPage />);

      await user.click(spinButton());
      await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("메뉴를 뽑는 중…"));

      // 80ms 간격이라 이 사이에 세 번은 바뀌었어야 한다. SLOT_EMOJIS에 🎲는 없으므로
      // 한 번이라도 돌았다면 이 조회는 실패한다.
      await new Promise((resolve) => setTimeout(resolve, 300));

      expect(screen.getByText("🎲")).toBeInTheDocument();
    } finally {
      window.matchMedia = original;
    }
  });
});

/**
 * 태그 <b>제안</b> 칩에 {@code chipToggle(false)}가 붙어 aria-pressed="false"로 읽혔다.
 *
 * <p>aria-pressed는 "눌러서 켜고 다시 눌러 끌 수 있다"는 약속이다. 그런데 이 버튼은 눌러도
 * pressed가 true로 바뀌지 않는다 — 선택 목록으로 옮겨 가며 이 자리에서 언마운트된다.
 * 켜졌는지 확인하러 돌아온 자리에는 아무것도 없고, 다시 눌러 끌 수도 없다. 상태를 약속해
 * 놓고 지키지 않는 것은 상태를 아예 말하지 않는 것보다 나쁘다.
 *
 * <p>상태를 뗀 만큼 이름이 일을 대신해야 한다 — "#혼밥"만으로는 눌렀을 때 추가되는지
 * 검색되는지 지워지는지 알 수 없다.
 */
describe("태그 제안 칩", () => {
  it("토글이 아니므로 aria-pressed를 달지 않고, 이름이 무엇을 하는지 말한다", async () => {
    searchTagsMock.mockResolvedValue([HONBAP]);
    renderWithProviders(<PickPage />);

    // 포함/제외 두 필터가 같은 키로 조회하므로 제안 칩도 두 벌 나온다.
    const suggestions = await screen.findAllByRole("button", { name: "혼밥 태그 추가" });
    expect(suggestions.length).toBeGreaterThan(0);
    for (const chip of suggestions) {
      expect(chip).not.toHaveAttribute("aria-pressed");
    }
  });

  it("반대로 선택된 칩은 눌러서 해제되는 진짜 토글이라 aria-pressed가 남는다", async () => {
    const user = userEvent.setup();
    searchTagsMock.mockResolvedValue([HONBAP]);
    renderWithProviders(<PickPage />);

    await user.click((await screen.findAllByRole("button", { name: "혼밥 태그 추가" }))[0]);

    // 이 칩은 눌러도 사라지지 않고 pressed=false로 돌아온다 — 약속이 지켜지는 쪽이다.
    expect(screen.getByRole("button", { name: "혼밥 태그 선택 해제" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });
});

/**
 * 필터 뭉치를 담은 {@code <section>}에 접근 가능한 이름이 없었다.
 *
 * <p>이름 없는 {@code <section>}은 랜드마크로 노출되지 않아 실질적으로 {@code <div>}다.
 * 스크린리더의 랜드마크 목록에 잡히지 않으니 필터를 건너뛰어 뽑기 버튼으로 가거나 반대로
 * 필터로 되돌아오려면 fieldset 네 개를 Tab으로 헤집는 수밖에 없었다.
 */
describe("필터 섹션 랜드마크", () => {
  it("제목으로 이름이 붙어 랜드마크로 잡힌다", () => {
    renderWithProviders(<PickPage />);

    const filters = screen.getByRole("region", { name: "픽 조건" });

    // 이름만 맞고 엉뚱한 요소에 붙었으면 안 된다 — 실제 필터를 감싸고 있어야 한다.
    expect(within(filters).getByRole("group", { name: "카테고리" })).toBeInTheDocument();
    expect(within(filters).getByRole("group", { name: "거리" })).toBeInTheDocument();
  });
});

/**
 * 거리 선택지는 항상 정확히 하나가 선택돼 있고 해제할 수단이 없다 — 토글 버튼이 아니라
 * 단일 선택 그룹이다. aria-pressed로 두면 스크린리더가 "500m 이내, 눌림"만 읽고 방금
 * 300m가 풀렸다는 사실은 어디에서도 전달되지 않아, 선택이 옮겨 간 것이 아니라 하나가
 * 더 켜진 것처럼 들린다.
 *
 * 역할만 바꾸는 것은 더 나쁘다 — role="radio"는 "화살표 키로 옮겨 다닐 수 있다"는 약속이고
 * 사용자는 실제로 그렇게 조작한다. 그래서 역할·roving tabindex·화살표 이동을 함께 본다.
 */
describe("PickPage 거리 선택 — 단일 선택 그룹", () => {
  async function readyWithDistance() {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);
    await user.click(distanceToggle());
    await waitFor(() => expect(pendingRequests).toHaveLength(1));
    resolveRequest(0, 37.5, 127.0);
    await screen.findByRole("radiogroup", { name: "거리" });
    return user;
  }

  it("선택지가 라디오로 노출되고 지금 선택된 하나만 checked다", async () => {
    await readyWithDistance();

    const radios = screen.getAllByRole("radio");
    expect(radios).toHaveLength(4);
    expect(screen.getByRole("radio", { name: "500m 이내" })).toBeChecked();
    expect(radios.filter((radio) => radio.getAttribute("aria-checked") === "true")).toHaveLength(1);
  });

  it("다른 선택지를 누르면 이전 선택이 풀린다", async () => {
    const user = await readyWithDistance();

    await user.click(screen.getByRole("radio", { name: "1km 이내" }));

    expect(screen.getByRole("radio", { name: "1km 이내" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "500m 이내" })).not.toBeChecked();
  });

  it("Tab 한 번이면 그룹 전체를 지나간다 (roving tabindex)", async () => {
    // 선택지가 넷인데 Tab을 넷 눌러야 한다면, 그룹이 늘어날수록 키보드 사용자만 비용을 문다.
    await readyWithDistance();

    const radios = screen.getAllByRole("radio");
    expect(radios.filter((radio) => radio.tabIndex === 0)).toHaveLength(1);
    expect(screen.getByRole("radio", { name: "500m 이내" })).toHaveAttribute("tabindex", "0");
  });

  it("화살표 키로 선택이 옮겨 가고 초점도 함께 간다", async () => {
    const user = await readyWithDistance();

    screen.getByRole("radio", { name: "500m 이내" }).focus();
    await user.keyboard("{ArrowRight}");

    expect(screen.getByRole("radio", { name: "1km 이내" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "1km 이내" })).toHaveFocus();
    expect(screen.getByRole("radio", { name: "1km 이내" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("radio", { name: "500m 이내" })).toHaveAttribute("tabindex", "-1");
  });

  it("끝에서 화살표를 더 누르면 반대쪽 끝으로 돈다", async () => {
    const user = await readyWithDistance();

    screen.getByRole("radio", { name: "500m 이내" }).focus();
    await user.keyboard("{ArrowLeft}{ArrowLeft}");

    expect(screen.getByRole("radio", { name: "2km 이내" })).toBeChecked();
  });

  it("Home·End로 양 끝으로 간다", async () => {
    const user = await readyWithDistance();

    screen.getByRole("radio", { name: "500m 이내" }).focus();
    await user.keyboard("{End}");
    expect(screen.getByRole("radio", { name: "2km 이내" })).toBeChecked();

    await user.keyboard("{Home}");
    expect(screen.getByRole("radio", { name: "300m 이내" })).toBeChecked();
  });

  it("고른 값이 픽 요청에 그대로 실린다", async () => {
    // 역할을 바꾸면서 onClick 배선이 끊기면 화면만 멀쩡하고 요청은 옛 값으로 나간다.
    const user = await readyWithDistance();

    await user.click(screen.getByRole("radio", { name: "2km 이내" }));
    await user.click(spinButton());

    await waitFor(() => expect(requestPickMock).toHaveBeenCalled());
    expect(requestPickMock.mock.calls.at(-1)![0].maxDistance).toBe(2000);
  });
});

/**
 * 픽 결과 카드(식권)의 발권 시각.
 *
 * <p>이 시각은 장식이 아니다 — 히스토리는 시각순으로 쌓이고 같은 메뉴가 여러 번 나올 수
 * 있어서, 방금 뽑은 그 한 건을 나중에 짚으려면 이름이 아니라 시각이 필요하다. 그래서
 * 여기 찍힌 값은 <b>픽이 도착한 순간</b>이어야 하고 그 뒤로 움직이면 안 된다.
 *
 * <p>렌더 안에서 그냥 {@code new Date()}를 부르면 화면만 보고는 멀쩡하다 — 처음 뜰 때는
 * 맞는 값이 찍히기 때문이다. 어긋나는 건 그 뒤에 카드가 다시 그려질 때이고(필터를 하나
 * 누르는 것만으로 일어난다), 그때부터 표에 찍힌 시각은 "뽑은 때"가 아니라 "마지막으로
 * 화면이 갱신된 때"가 된다. 히스토리에서 그 시각을 찾으면 아무것도 안 나온다.
 */
describe("PickPage 식권 발권 시각", () => {
  it("결과가 나온 순간에 고정되고 다시 그려져도 움직이지 않는다", async () => {
    // shouldAdvanceTime: 실제 시간이 흐르면 가짜 시계도 따라 흐른다 — 픽 연출의
    // 최소 대기(1.2초)가 그대로 끝나야 결과 카드가 나온다.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      vi.setSystemTime(new Date("2026-01-15T14:07:30"));
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      renderWithProviders(<PickPage />);

      await user.click(spinButton());
      const ticketTime = await screen.findByText("14:07", {}, { timeout: 3000 });
      expect(ticketTime).toHaveAttribute("datetime", expect.stringContaining("2026-01-15"));

      // 한참 뒤에 카드가 다시 그려지는 상황. 결과는 그대로 붙어 있고 필터만 건드린다.
      vi.setSystemTime(new Date("2026-01-15T15:42:00"));
      await user.click(screen.getByRole("button", { name: "한식" }));

      expect(screen.getByText("14:07")).toBeInTheDocument();
      expect(screen.queryByText("15:42")).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});

/**
 * 후보가 빈 이유는 셋이고 사용자가 해야 할 일이 각각 다르다.
 *
 * 전에는 셋을 하나로 뭉쳐 "필터를 풀거나 메뉴를 추가해 보세요"라고만 했다. 기본 메뉴 22개를
 * 받고 시작한 신규 사용자가 거리 필터를 켜면 정확히 이 화면을 보는데, 그에게 메뉴를 더
 * 추가하라는 것은 틀린 조언이다 — 메뉴는 넘치고 없는 것은 식당 연결이다.
 */
describe("PickPage 후보가 없을 때의 안내", () => {
  /** 백엔드 에러 코드를 실은 axios 형태의 거절. apiErrorCode가 읽는 자리와 같아야 한다. */
  const apiError = (errorCode: string) =>
    Object.assign(new Error("Request failed"), {
      isAxiosError: true,
      response: { status: 404, data: { success: false, errorCode, message: "없음" } },
    });

  it("식당 연결이 없으면 식당 화면으로 보낸다 — 메뉴를 추가하라고 하지 않는다", async () => {
    const user = userEvent.setup();
    requestPickMock.mockRejectedValue(apiError("NO_LINKED_RESTAURANTS"));
    renderWithProviders(<PickPage />);

    await user.click(spinButton());

    expect(
      await screen.findByText(/식당이 연결되어 있어야 해요/, {}, { timeout: 3000 }),
    ).toBeInTheDocument();
    // 반경을 넓히라고 하면 안 된다 — 연결이 0건이면 아무리 넓혀도 결과가 없다.
    expect(screen.queryByText(/반경을 넓혀 보세요/)).toBeNull();
    expect(screen.getByRole("link", { name: /식당 관리하러 가기/ })).toHaveAttribute(
      "href",
      "/restaurants",
    );
  });

  it("뽑을 메뉴가 없으면 메뉴 화면으로 보내고 추천 제외도 짚어 준다", async () => {
    const user = userEvent.setup();
    requestPickMock.mockRejectedValue(apiError("NO_PICKABLE_MENUS"));
    renderWithProviders(<PickPage />);

    await user.click(spinButton());

    expect(
      await screen.findByText(/뽑을 메뉴가 없어요/, {}, { timeout: 3000 }),
    ).toBeInTheDocument();
    // 메뉴가 있는데 전부 제외해 둔 경우도 같은 코드로 오므로 그 길도 알려 줘야 한다.
    expect(screen.getByText(/다시 포함시켜 주세요/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /내 메뉴 관리하러 가기/ })).toHaveAttribute(
      "href",
      "/menus",
    );
  });

  it("조건이 좁은 경우에는 필터와 반경을 풀라고 안내한다", async () => {
    const user = userEvent.setup();
    requestPickMock.mockRejectedValue(apiError("NO_PICK_CANDIDATES"));
    renderWithProviders(<PickPage />);

    await user.click(spinButton());

    expect(
      await screen.findByText(/조건에 맞는 메뉴가 없어요/, {}, { timeout: 3000 }),
    ).toBeInTheDocument();
    // 여기서는 연결이 있으므로 반경을 넓히면 실제로 결과가 나온다.
    expect(screen.getByText(/반경을 넓혀 보세요/)).toBeInTheDocument();
  });

  it("모르는 에러는 안내 카드가 아니라 일반 에러로 띄운다", async () => {
    const user = userEvent.setup();
    requestPickMock.mockRejectedValue(apiError("SERVER_ERROR"));
    renderWithProviders(<PickPage />);

    await user.click(spinButton());

    // 빈 결과 안내를 아무 실패에나 붙이면 서버 장애가 "메뉴가 없다"로 둔갑한다.
    expect(await screen.findByRole("alert", {}, { timeout: 3000 })).toBeInTheDocument();
    expect(screen.queryByText(/관리하러 가기/)).toBeNull();
  });
});
