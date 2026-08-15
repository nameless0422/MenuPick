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

const editButton = () => screen.getByRole("button", { name: "수정" });
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

    await user.click(await screen.findByRole("button", { name: "수정" }));

    const heading = await screen.findByRole("heading", { name: "메뉴 수정" });
    await waitFor(() => expect(heading).toHaveFocus());
  });

  it("폼을 닫으면 초점이 눌렀던 '수정' 버튼으로 돌아간다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<MenusPage />);

    await user.click(await screen.findByRole("button", { name: "수정" }));
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

    await user.click(await screen.findByRole("button", { name: "수정" }));
    await screen.findByRole("heading", { name: "메뉴 수정" });

    // 이 메뉴는 "한식"만 가지고 있다.
    expect(screen.getByRole("button", { name: "한식", pressed: true })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "중식", pressed: false })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "중식" }));

    expect(screen.getByRole("button", { name: "중식", pressed: true })).toBeInTheDocument();
  });
});
