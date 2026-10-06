import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError } from "axios";
import { renderWithProviders } from "../test/renderWithProviders";
import { fetchDeletedMenus, restoreMenu } from "../api/menus";
import MenuTrash from "./MenuTrash";

vi.mock("../api/menus", () => ({ fetchDeletedMenus: vi.fn(), restoreMenu: vi.fn() }));
const fetchMock = vi.mocked(fetchDeletedMenus);
const restoreMock = vi.mocked(restoreMenu);
const menu = { id: 50, name: "김치찌개", deletedAt: "2026-10-07T00:15:00", version: 3 };
const page = { menus: [menu], nextCursor: null, hasNext: false };

beforeEach(() => {
  fetchMock.mockReset().mockResolvedValue(page);
  restoreMock.mockReset().mockResolvedValue(undefined);
});

describe("메뉴 휴지통", () => {
  async function open(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole("button", { name: "휴지통 보기" }));
    return screen.findByRole("list", { name: "삭제한 메뉴" });
  }

  it("휴지통을 열 때만 조회하며 한국 시각의 삭제 날짜를 표시한다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<MenuTrash />);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "휴지통 보기" })).toHaveAttribute("aria-expanded", "false");
    const list = await open(user);
    expect(fetchMock).toHaveBeenCalledWith(undefined, 20);
    expect(within(list).getByText("삭제일: 2026-10-07")).toHaveAttribute("datetime", "2026-10-07");
    await user.click(screen.getByRole("button", { name: "휴지통 닫기" }));
    expect(screen.queryByRole("list", { name: "삭제한 메뉴" })).not.toBeInTheDocument();
  });

  it("삭제한 메뉴가 없으면 빈 휴지통 안내를 표시한다", async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValue({ menus: [], nextCursor: null, hasNext: false });
    renderWithProviders(<MenuTrash />);
    await user.click(screen.getByRole("button", { name: "휴지통 보기" }));
    expect(await screen.findByText("삭제한 메뉴가 없습니다.")).toBeInTheDocument();
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });

  it("복원할 때 조회한 버전을 전달하고 마지막 항목이 사라지면 토글로 초점을 옮긴다", async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValueOnce(page).mockResolvedValue({ menus: [], nextCursor: null, hasNext: false });
    renderWithProviders(<MenuTrash />);
    await open(user);
    await user.click(screen.getByRole("button", { name: "김치찌개 복원" }));
    expect(await screen.findByText("삭제한 메뉴가 없습니다.")).toBeInTheDocument();
    expect(restoreMock).toHaveBeenCalledWith(50, 3);
    expect(screen.getByRole("status")).toHaveTextContent("'김치찌개' 메뉴를 복원했습니다.");
    await waitFor(() => expect(screen.getByRole("button", { name: "휴지통 닫기" })).toHaveFocus());
  });

  it("남은 항목이 있으면 목록으로 초점을 옮긴다", async () => {
    const user = userEvent.setup();
    const other = { ...menu, id: 40, name: "된장찌개" };
    fetchMock.mockResolvedValueOnce({ ...page, menus: [menu, other] }).mockResolvedValue({ ...page, menus: [other] });
    renderWithProviders(<MenuTrash />);
    await open(user);
    await user.click(screen.getByRole("button", { name: "김치찌개 복원" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "김치찌개 복원" })).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole("list", { name: "삭제한 메뉴" })).toHaveFocus());
  });

  it("복원 중 연타를 막고 사용자가 옮긴 초점은 유지한다", async () => {
    const user = userEvent.setup();
    let finish!: () => void;
    const saving = new Promise<void>((resolve) => { finish = resolve; });
    restoreMock.mockReturnValue(saving);
    fetchMock.mockResolvedValueOnce(page).mockResolvedValue({ menus: [], nextCursor: null, hasNext: false });
    renderWithProviders(<MenuTrash />);
    await open(user);
    const restore = screen.getByRole("button", { name: "김치찌개 복원" });
    await user.click(restore);
    expect(restore).toHaveAttribute("aria-disabled", "true");
    expect(restore).toHaveFocus();
    await user.click(restore);
    expect(restoreMock).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "메뉴 휴지통 새로고침" }));
    const refresh = screen.getByRole("button", { name: "메뉴 휴지통 새로고침" });
    await act(async () => { finish(); await saving; });
    await screen.findByText("삭제한 메뉴가 없습니다.");
    expect(refresh).toHaveFocus();
  });

  it("다음 페이지의 커서를 전달하고 기존 항목을 유지한다", async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValueOnce({ ...page, nextCursor: "cursor-50", hasNext: true })
      .mockResolvedValueOnce({ menus: [{ ...menu, id: 1, name: "예전 메뉴" }], nextCursor: null, hasNext: false });
    renderWithProviders(<MenuTrash />);
    await open(user);
    await user.click(screen.getByRole("button", { name: "삭제한 메뉴 더 보기" }));
    expect(await screen.findByText("예전 메뉴")).toBeInTheDocument();
    expect(screen.getByText("김치찌개")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenLastCalledWith("cursor-50", 20);
    expect(screen.queryByRole("button", { name: "삭제한 메뉴 더 보기" })).not.toBeInTheDocument();
  });

  it("더 보기 실패 후에도 기존 목록을 표시하고 같은 커서로 재시도한다", async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValueOnce({ ...page, nextCursor: "cursor-50", hasNext: true }).mockRejectedValueOnce(new Error("추가 조회 실패"));
    renderWithProviders(<MenuTrash />);
    await open(user);
    await user.click(screen.getByRole("button", { name: "삭제한 메뉴 더 보기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("추가 조회 실패");
    expect(screen.getByText("김치찌개")).toBeInTheDocument();
    fetchMock.mockResolvedValue({ menus: [{ ...menu, id: 1, name: "예전 메뉴" }], nextCursor: null, hasNext: false });
    await user.click(screen.getByRole("button", { name: "삭제한 메뉴 더 보기" }));
    await screen.findByText("예전 메뉴");
    expect(fetchMock).toHaveBeenLastCalledWith("cursor-50", 20);
  });

  it("조회 실패 후 새로고침으로 복구한다", async () => {
    const user = userEvent.setup();
    fetchMock.mockRejectedValueOnce(new Error("휴지통 조회 실패")).mockResolvedValue(page);
    renderWithProviders(<MenuTrash />);
    await user.click(screen.getByRole("button", { name: "휴지통 보기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("휴지통 조회 실패");
    await user.click(screen.getByRole("button", { name: "메뉴 휴지통 새로고침" }));
    expect(await screen.findByText("김치찌개")).toBeInTheDocument();
  });

  it("복원 버전 충돌 시 목록을 갱신해 새 버전으로 다시 요청할 수 있다", async () => {
    const user = userEvent.setup();
    const conflict = new AxiosError("conflict", undefined, undefined, undefined, {
      status: 409, data: { success: false, errorCode: "CONCURRENT_MODIFICATION", message: "메뉴 상태가 바뀌었습니다." },
      statusText: "Conflict", headers: {}, config: {} as never,
    });
    restoreMock.mockRejectedValueOnce(conflict).mockResolvedValue(undefined);
    fetchMock.mockResolvedValueOnce(page).mockResolvedValue({ ...page, menus: [{ ...menu, version: 5 }] });
    renderWithProviders(<MenuTrash />);
    await open(user);
    await user.click(screen.getByRole("button", { name: "김치찌개 복원" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("메뉴 상태가 바뀌었습니다.");
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByRole("button", { name: "김치찌개 복원" })).not.toHaveAttribute("aria-disabled"));
    await user.click(screen.getByRole("button", { name: "김치찌개 복원" }));
    await waitFor(() => expect(restoreMock).toHaveBeenLastCalledWith(50, 5));
  });
});
