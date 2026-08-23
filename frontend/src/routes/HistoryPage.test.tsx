import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import HistoryPage from "./HistoryPage";
import { fetchHistories, fetchMenuRestaurants } from "../api/history";

vi.mock("../api/history", () => ({
  fetchHistories: vi.fn(),
  fetchMenuRestaurants: vi.fn(),
  markVisited: vi.fn(),
  deleteHistory: vi.fn(),
}));
vi.mock("../api/menus", () => ({
  fetchMenus: vi.fn().mockResolvedValue({ menus: [{ id: 1, name: "김치찌개" }] }),
}));

const fetchHistoriesMock = vi.mocked(fetchHistories);
const fetchMenuRestaurantsMock = vi.mocked(fetchMenuRestaurants);

const KIMCHI_PICK = {
  id: 10,
  menuName: "김치찌개",
  restaurantName: "진주회관",
  isVisited: false,
  recommendedAt: "2026-08-21T19:30:00",
  visitedAt: null,
  filterConditions: [],
};

beforeEach(() => {
  fetchHistoriesMock.mockReset();
  fetchMenuRestaurantsMock.mockReset();
  fetchMenuRestaurantsMock.mockResolvedValue([]);
  fetchHistoriesMock.mockResolvedValue({
    histories: [KIMCHI_PICK],
    nextCursor: null,
    hasNext: false,
  });
});

/**
 * 히스토리 카드 하나에 "방문했어요"와 "삭제"가 나란히 있고, 그 카드가 기록 수만큼 반복된다.
 *
 * <p>NVDA의 요소 목록이나 JAWS의 B 키 순회는 <b>버튼 이름만</b> 늘어놓는다. 이름에 항목이
 * 드러나지 않으면 "방문했어요, 삭제, 방문했어요, 삭제 …"만 남는다. 삭제는 되돌릴 수 없는데도
 * 확인 대화상자마저 무엇을 지우는지 말하지 않았다.
 *
 * <p>같은 메뉴를 여러 번 뽑을 수 있으므로 이름만으로는 항목이 구분되지 않는다 — 시각까지 넣는다.
 */
describe("히스토리 목록의 이름", () => {
  it("삭제 버튼이 어느 픽 기록의 것인지 시각까지 밝힌다", async () => {
    renderWithProviders(<HistoryPage />);

    expect(
      await screen.findByRole("button", { name: "김치찌개 픽 기록 삭제 (8월 21일 (금) 19:30)" }),
    ).toBeInTheDocument();
  });

  it("확인 대화상자도 무엇을 지우는지 말한다", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    renderWithProviders(<HistoryPage />);

    await user.click(await screen.findByRole("button", { name: /김치찌개 픽 기록 삭제/ }));

    expect(confirmSpy).toHaveBeenCalledWith("'김치찌개' 픽 기록을 삭제할까요?");
    confirmSpy.mockRestore();
  });

  it("방문 버튼이 어느 픽의 것인지 밝힌다", async () => {
    renderWithProviders(<HistoryPage />);

    expect(await screen.findByRole("button", { name: "김치찌개 방문했어요" })).toBeInTheDocument();
  });

  it("메뉴가 삭제된 기록도 이름 없는 버튼을 남기지 않는다", async () => {
    fetchHistoriesMock.mockResolvedValue({
      histories: [{ ...KIMCHI_PICK, menuName: null }],
      nextCursor: null,
      hasNext: false,
    });

    renderWithProviders(<HistoryPage />);

    expect(await screen.findByRole("button", { name: /삭제된 메뉴 픽 기록 삭제/ })).toBeInTheDocument();
    // VisitAction은 menuName이 null이면 "이 픽"으로 부른다 — 빈 이름을 만들지 않는다.
    expect(screen.getByRole("button", { name: "이 픽 방문했어요" })).toBeInTheDocument();
  });
});

/**
 * 기간 필터를 바꾸면 목록이 통째로 갈리는데, 화면 아래가 조용히 다시 그려질 뿐이었다.
 * 삭제도 마찬가지로 항목만 사라지고 아무 소리가 나지 않았다.
 */
describe("목록 상태 통지", () => {
  it("몇 개인지 알린다", async () => {
    renderWithProviders(<HistoryPage />);

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("픽 기록 1개"));
  });

  it("삭제 성공을 이름과 함께 알린다", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    renderWithProviders(<HistoryPage />);

    await user.click(await screen.findByRole("button", { name: /김치찌개 픽 기록 삭제/ }));

    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("'김치찌개' 픽 기록을 삭제했습니다."),
    );
    confirmSpy.mockRestore();
  });

  it("결과가 0건이면 빈 목록을 남기지 않는다", async () => {
    fetchHistoriesMock.mockResolvedValue({ histories: [], nextCursor: null, hasNext: false });

    renderWithProviders(<HistoryPage />);

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(/아직 픽 기록이 없어요/));
    // 빈 <ul>이 남으면 "목록, 항목 0개"로 읽혀 위 안내와 어긋난다.
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });
});

/**
 * 날짜는 기계가 읽을 수 있는 형태로도 남겨야 하고, "전체"(3650일) 필터에서는 연도가 없으면
 * 작년 기록과 올해 기록이 똑같이 읽힌다.
 */
describe("날짜 표기", () => {
  it("<time datetime>으로 원본 시각을 남긴다", async () => {
    renderWithProviders(<HistoryPage />);

    const time = (await screen.findByText(/8월 21일/)).closest("time");
    expect(time).toHaveAttribute("datetime", "2026-08-21T19:30:00");
  });

  it("올해가 아닌 기록에는 연도를 붙인다", async () => {
    fetchHistoriesMock.mockResolvedValue({
      histories: [{ ...KIMCHI_PICK, recommendedAt: "2019-03-04T12:00:00" }],
      nextCursor: null,
      hasNext: false,
    });

    renderWithProviders(<HistoryPage />);

    expect(await screen.findByText(/2019년 3월 4일/)).toBeInTheDocument();
  });
});

/**
 * 삭제 버튼을 누른 그 <li>가 사라진다. window.confirm은 브라우저가 원래 버튼으로 초점을
 * 되돌려 주지만, 삭제가 성공하는 순간 그 버튼이 DOM에서 없어져 초점은 결국 {@code <body>}로
 * 떨어진다. 화면만 보면 아무 문제가 없어 보이고, 키보드 사용자만 다음 기록을 지우려고
 * 페이지 맨 위에서 Tab을 다시 눌러 내려와야 한다.
 */
describe("HistoryPage 삭제 후 초점", () => {
  const OTHER_PICK = { ...KIMCHI_PICK, id: 11, menuName: "된장찌개" };

  it("기록이 남아 있으면 초점이 목록으로 간다", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    fetchHistoriesMock.mockResolvedValueOnce({
      histories: [KIMCHI_PICK, OTHER_PICK],
      nextCursor: null,
      hasNext: false,
    });
    // 삭제 뒤 다시 불러오면 한 건만 남는다.
    fetchHistoriesMock.mockResolvedValue({
      histories: [OTHER_PICK],
      nextCursor: null,
      hasNext: false,
    });

    renderWithProviders(<HistoryPage />);

    await user.click(await screen.findByRole("button", { name: /김치찌개 픽 기록 삭제/ }));

    await waitFor(() => expect(screen.getByRole("list")).toHaveFocus());
    // 목록이 다시 그려져도 같은 <ul>이라 초점은 그대로 남아 있어야 한다.
    await waitFor(() => expect(screen.queryByText("김치찌개")).not.toBeInTheDocument());
    expect(screen.getByRole("list")).toHaveFocus();
    confirmSpy.mockRestore();
  });

  it("마지막 기록을 지우면 목록이 사라지므로 초점이 제목으로 간다", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    fetchHistoriesMock.mockResolvedValueOnce({
      histories: [KIMCHI_PICK],
      nextCursor: null,
      hasNext: false,
    });
    fetchHistoriesMock.mockResolvedValue({ histories: [], nextCursor: null, hasNext: false });

    renderWithProviders(<HistoryPage />);

    await user.click(await screen.findByRole("button", { name: /김치찌개 픽 기록 삭제/ }));

    // <ul>로 보냈다면 목록이 언마운트되는 순간 초점이 다시 <body>로 떨어진다.
    await waitFor(() => expect(screen.getByRole("heading", { name: "픽 히스토리" })).toHaveFocus());
    await waitFor(() => expect(screen.queryByRole("list")).not.toBeInTheDocument());
    expect(screen.getByRole("heading", { name: "픽 히스토리" })).toHaveFocus();
    confirmSpy.mockRestore();
  });
});
