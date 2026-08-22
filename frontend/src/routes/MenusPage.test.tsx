import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import MenusPage from "./MenusPage";
import { fetchMenu, fetchMenus } from "../api/menus";

vi.mock("../api/menus", () => ({
  fetchMenus: vi.fn(),
  fetchMenu: vi.fn(),
  createMenu: vi.fn(),
  updateMenu: vi.fn(),
  deleteMenu: vi.fn(),
  toggleExclude: vi.fn(),
}));
vi.mock("../api/tags", () => ({
  searchTags: vi.fn().mockResolvedValue([]),
  createTag: vi.fn(),
}));

const fetchMenusMock = vi.mocked(fetchMenus);
const fetchMenuMock = vi.mocked(fetchMenu);

const KIMCHI = {
  id: 1,
  name: "김치찌개",
  weight: 3,
  isExcluded: false,
  categories: ["한식"],
  tags: [],
};

beforeEach(() => {
  fetchMenusMock.mockReset();
  fetchMenuMock.mockReset();
  fetchMenusMock.mockResolvedValue({ menus: [KIMCHI], nextCursor: null, hasNext: false });
  fetchMenuMock.mockResolvedValue({
    ...KIMCHI,
    memo: null,
    createdAt: "2026-01-01T00:00:00",
    updatedAt: "2026-01-01T00:00:00",
  });
});

const editButton = () => screen.getByRole("button", { name: "김치찌개 수정" });
const newMenuButton = () => screen.getByRole("button", { name: "+ 새 메뉴" });

/**
 * 수정 폼은 카드를 통째로 대체한다 — 방금 누른 "수정" 버튼이 DOM에서 사라진다는 뜻이다.
 * 초점을 옮겨두지 않으면 브라우저가 이를 {@code <body>}로 되돌리고, 그때부터 Tab은
 * 폼이 아니라 페이지 맨 위 내비게이션으로 간다. 화면만 보면 아무 문제가 없어 보인다.
 */
describe("MenusPage 폼 초점", () => {
  it("수정 폼이 열리면 초점이 폼 제목으로 간다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<MenusPage />);

    await user.click(await screen.findByRole("button", { name: "김치찌개 수정" }));

    const heading = await screen.findByRole("heading", { name: "메뉴 수정" });
    await waitFor(() => expect(heading).toHaveFocus());
  });

  it("폼을 닫으면 초점이 눌렀던 '수정' 버튼으로 돌아간다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<MenusPage />);

    await user.click(await screen.findByRole("button", { name: "김치찌개 수정" }));
    await screen.findByRole("heading", { name: "메뉴 수정" });

    await user.click(screen.getByRole("button", { name: "취소" }));

    // 폼과 함께 사라졌다가 다시 그려진 버튼이라, 닫기 전에 잡아 둔 참조로는 못 돌아간다.
    await waitFor(() => expect(editButton()).toHaveFocus());
  });

  it("새 메뉴 폼을 닫으면 초점이 '+ 새 메뉴' 버튼으로 돌아간다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<MenusPage />);

    await user.click(newMenuButton());
    const heading = await screen.findByRole("heading", { name: "새 메뉴" });
    await waitFor(() => expect(heading).toHaveFocus());

    await user.click(screen.getByRole("button", { name: "취소" }));

    await waitFor(() => expect(newMenuButton()).toHaveFocus());
  });
});

describe("MenusPage 카테고리 칩", () => {
  it("선택 여부를 aria-pressed로 알린다 — 색만으로는 스크린리더에 전달되지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<MenusPage />);

    await user.click(await screen.findByRole("button", { name: "김치찌개 수정" }));
    await screen.findByRole("heading", { name: "메뉴 수정" });

    // 이 메뉴는 "한식"만 가지고 있다.
    expect(screen.getByRole("button", { name: "한식", pressed: true })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "중식", pressed: false })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "중식" }));

    expect(screen.getByRole("button", { name: "중식", pressed: true })).toBeInTheDocument();
  });
});

describe("선호도 별점 접근성", () => {
  it("현재 선호도를 색이 아니라 aria-pressed로도 알린다", async () => {
    // 스크린리더에는 색이 전달되지 않는다. aria-pressed가 없으면 "선호도 1 버튼 …
    // 선호도 5 버튼"이 상태 없이 읽혀, 지금이 3인지 5인지 알 방법이 없다.
    const user = userEvent.setup();
    renderWithProviders(<MenusPage />);

    await user.click(await screen.findByRole("button", { name: "김치찌개 수정" }));

    // KIMCHI는 weight 3 — 채워진 별 셋이 눌린 상태다
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "선호도 3" })).toHaveAttribute(
        "aria-pressed",
        "true",
      ),
    );
    expect(screen.getByRole("button", { name: "선호도 1" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "선호도 4" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "선호도 5" })).toHaveAttribute("aria-pressed", "false");

    await user.click(screen.getByRole("button", { name: "선호도 5" }));

    expect(screen.getByRole("button", { name: "선호도 4" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "선호도 5" })).toHaveAttribute("aria-pressed", "true");
  });

  it("별 묶음에 이름이 있어 무엇을 고르는 것인지 알 수 있다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<MenusPage />);

    await user.click(await screen.findByRole("button", { name: "김치찌개 수정" }));

    expect(await screen.findByRole("group", { name: "선호도" })).toBeInTheDocument();
  });

  /**
   * 별 묶음을 {@code <label>}로 감싸면 안 된다. {@code <button>}은 labelable 요소라
   * for 없는 label의 대상 컨트롤이 <b>첫 번째 별</b>이 되고, 그때부터 label 영역
   * 아무 곳이나 누르면 별 1이 눌린다. .menu-form label이 flex-column이라 그 영역은
   * 폼 전체 폭이다 — 즉 "선호도" 글자와 별 오른쪽 빈 공간 전체가 별 1의 클릭 영역이다.
   *
   * 화면만 보면 아무 문제가 없어 보이는데, 스크롤하려고 탭하거나 별 3을 겨냥하다
   * 살짝 빗나가면 점수가 조용히 1로 떨어진다.
   */
  it("라벨 영역을 눌러도 점수가 바뀌지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<MenusPage />);

    await user.click(await screen.findByRole("button", { name: "김치찌개 수정" }));

    const group = await screen.findByRole("group", { name: "선호도" });
    // KIMCHI는 weight 3에서 시작한다. 5로 올려 두면 회귀 시 1로 떨어지는 폭이 커진다.
    await user.click(screen.getByRole("button", { name: "선호도 5" }));
    expect(screen.getByRole("button", { name: "선호도 5" })).toHaveAttribute("aria-pressed", "true");

    // 별 묶음을 감싼 컨테이너(옛 <label>) 자체를 클릭한다.
    const container = group.parentElement!;
    await user.click(container);

    // label이었다면 이 클릭이 별 1로 위임되어 5점이 1점이 된다.
    expect(screen.getByRole("button", { name: "선호도 5" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "선호도 2" })).toHaveAttribute("aria-pressed", "true");
  });

  /**
   * 빈 별을 색으로만 구분하면(이전에는 --border, 배경 대비 1.27:1) 화면에서 사라져
   * "5점 만점에 3점"이 아니라 "별 세 개"로만 보인다. 척도가 몇 점인지 알 수 없으니
   * 3점이 높은 점수인지 낮은 점수인지도 판단할 수 없다. 읽기 전용 별점이 이미
   * ★/☆로 구분하고 있으므로 편집 위젯도 같은 방식이어야 한다.
   */
  it("채운 별과 빈 별을 색이 아니라 글리프로 구분한다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<MenusPage />);

    await user.click(await screen.findByRole("button", { name: "김치찌개 수정" }));

    // KIMCHI는 weight 3
    expect(await screen.findByRole("button", { name: "선호도 3" })).toHaveTextContent("★");
    expect(screen.getByRole("button", { name: "선호도 4" })).toHaveTextContent("☆");
    expect(screen.getByRole("button", { name: "선호도 5" })).toHaveTextContent("☆");

    await user.click(screen.getByRole("button", { name: "선호도 5" }));

    expect(screen.getByRole("button", { name: "선호도 4" })).toHaveTextContent("★");
    expect(screen.getByRole("button", { name: "선호도 5" })).toHaveTextContent("★");
  });

  it("별 묶음을 감싼 요소가 label이 아니다", async () => {
    // 위 테스트는 jsdom의 label 위임 구현에 기대므로, 구조 자체도 직접 못 박아 둔다.
    const user = userEvent.setup();
    renderWithProviders(<MenusPage />);

    await user.click(await screen.findByRole("button", { name: "김치찌개 수정" }));

    const group = await screen.findByRole("group", { name: "선호도" });
    expect(group.closest("label")).toBeNull();
  });
});

/**
 * 목록 화면의 접근 가능한 이름.
 *
 * <p>NVDA의 요소 목록이나 JAWS의 B 키 순회는 <b>버튼 이름만</b> 늘어놓는다. 이름에 항목명이
 * 없으면 "수정, 추천에서 제외, 삭제"가 메뉴 수만큼 반복될 뿐이고, 앞의 {@code <strong>}은
 * 제목이 아니라 일반 텍스트라 항목 단위로 건너뛸 수도 없다. 게다가 "수정"과 "추천에서 제외"는
 * 확인 단계가 없어 잘못 누르면 그대로 실행된다.
 */
describe("목록의 이름", () => {
  it("액션 버튼 3종이 어느 메뉴의 것인지 이름으로 밝힌다", async () => {
    renderWithProviders(<MenusPage />);

    expect(await screen.findByRole("button", { name: "김치찌개 수정" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "김치찌개 추천에서 제외" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "김치찌개 삭제" })).toBeInTheDocument();
  });

  it("읽기 전용 선호도가 별 글자가 아니라 문장으로 읽힌다", async () => {
    renderWithProviders(<MenusPage />);

    // title은 generic <span>에서 이름으로 노출되지 않는다. 별 글자만 남으면 스크린리더는
    // "검은 별"을 세 번, "흰 별"을 두 번 읽거나 아무것도 읽지 않는다.
    expect(await screen.findByText("선호도 5점 만점에 3점")).toBeInTheDocument();
    expect(screen.getByText("★★★☆☆")).toHaveAttribute("aria-hidden", "true");
  });

  it("태그 검색과 카테고리 직접 입력에 이름이 있다 (placeholder만으로는 부족하다)", async () => {
    const user = userEvent.setup();
    renderWithProviders(<MenusPage />);

    await user.click(newMenuButton());

    expect(await screen.findByRole("textbox", { name: "직접 입력한 카테고리" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "태그 검색" })).toBeInTheDocument();
  });
});

/**
 * 목록이 바뀌었다는 사실 자체가 통지되지 않았다.
 *
 * <p>역설적으로 <b>실패에는 role="alert"가 있는데 성공은 무음</b>이었다. 삭제하면 항목이
 * 사라질 뿐이고 초점은 없어진 {@code <li>}와 함께 {@code <body>}로 떨어진다.
 *
 * <p>라이브 리전은 <b>내용보다 먼저 DOM에 있어야</b> 동작한다. 내용과 함께 삽입되는 리전은
 * 스크린리더가 놓치므로, 비어 있는 채로 마운트되는지까지 확인한다.
 */
describe("목록 상태 통지", () => {
  it("리전이 내용보다 먼저, 비어 있는 채로 마운트된다", () => {
    // 목록이 아직 안 왔을 때(isPending)도 리전 자체는 이미 있어야 한다.
    fetchMenusMock.mockReturnValue(new Promise(() => {}) as never);

    renderWithProviders(<MenusPage />);

    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("몇 개인지 알린다", async () => {
    renderWithProviders(<MenusPage />);

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("메뉴 1개"));
  });

  it("삭제 성공을 이름과 함께 알린다", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    renderWithProviders(<MenusPage />);

    await user.click(await screen.findByRole("button", { name: "김치찌개 삭제" }));

    // 삭제된 뒤에는 목록에서 사라지므로, 이름은 변이 인자에 실려 있어야만 남는다.
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("'김치찌개' 메뉴를 삭제했습니다."),
    );
    confirmSpy.mockRestore();
  });

  it("빈 상태 안내도 같은 리전 안에서 통지된다", async () => {
    fetchMenusMock.mockResolvedValue({ menus: [], nextCursor: null, hasNext: false });

    renderWithProviders(<MenusPage />);

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(/등록된 메뉴가 없습니다/));
  });
});
