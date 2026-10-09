import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError } from "axios";
import { renderWithProviders } from "../test/renderWithProviders";
import { fetchHistoryMemo, updateHistoryMemo } from "../api/history";
import HistoryMemo from "./HistoryMemo";

vi.mock("../api/history", () => ({ fetchHistoryMemo: vi.fn(), updateHistoryMemo: vi.fn() }));
const fetchMock = vi.mocked(fetchHistoryMemo);
const updateMock = vi.mocked(updateHistoryMemo);
const LABEL = "김치찌개 (10월 9일)";
const DETAIL = { historyId: 42, memo: null, version: 3 };
const conflict = () => new AxiosError("충돌", "ERR_BAD_REQUEST", undefined, undefined, {
  data: { success: false, errorCode: "CONCURRENT_MODIFICATION", message: "다른 곳에서 먼저 수정했습니다." },
  status: 409, statusText: "Conflict", headers: {}, config: {} as never,
});
const renderMemo = (memo: string | null = null) => renderWithProviders(<HistoryMemo historyId={42} label={LABEL} memo={memo} />);
const open = async (user: ReturnType<typeof userEvent.setup>, action = "쓰기") => {
  await user.click(screen.getByRole("button", { name: LABEL + " 메모 " + action }));
  return screen.findByRole("textbox", { name: "픽 기록 메모" });
};

beforeEach(() => {
  fetchMock.mockReset().mockResolvedValue(DETAIL);
  updateMock.mockReset().mockResolvedValue({ historyId: 42, memo: "덜 맵게", version: 4 });
});

describe("픽 기록 메모", () => {
  it("편집할 때만 최신 메모를 조회하고 입력란으로 초점을 옮긴다", async () => {
    const user = userEvent.setup();
    renderMemo();
    expect(fetchMock).not.toHaveBeenCalled();
    const input = await open(user);
    expect(fetchMock).toHaveBeenCalledWith(42);
    expect(input).toHaveFocus();
    expect(input).toHaveAttribute("maxlength", "500");
    expect(screen.getByText(/0\/500자/)).toBeVisible();
  });

  it("조회한 버전으로 저장하고 저장 후 초점을 돌려준다", async () => {
    const user = userEvent.setup();
    renderMemo();
    await user.type(await open(user), "덜 맵게");
    await user.click(screen.getByRole("button", { name: "메모 저장" }));
    await screen.findByText("메모를 저장했습니다.");
    expect(updateMock).toHaveBeenCalledWith(42, "덜 맵게", 3);
    expect(screen.getByRole("button", { name: LABEL + " 메모 쓰기" })).toHaveFocus();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("취소하면 저장하지 않고 열기 버튼으로 돌아간다", async () => {
    const user = userEvent.setup();
    renderMemo();
    await user.type(await open(user), "작성 중");
    await user.click(screen.getByRole("button", { name: "메모 편집 취소" }));
    expect(updateMock).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: LABEL + " 메모 쓰기" })).toHaveFocus();
  });

  it("충돌 시 초안을 유지하며 최신 메모를 확인한 뒤 그 버전으로 다시 저장한다", async () => {
    fetchMock.mockResolvedValueOnce(DETAIL).mockResolvedValue({ ...DETAIL, memo: "서버 메모", version: 5 });
    updateMock.mockRejectedValueOnce(conflict()).mockResolvedValue({ ...DETAIL, memo: "내 초안", version: 6 });
    const user = userEvent.setup();
    renderMemo();
    await user.type(await open(user), "내 초안");
    await user.click(screen.getByRole("button", { name: "메모 저장" }));
    await screen.findByText(/작성한 내용은 유지했습니다/);
    expect(screen.getByRole("textbox")).toHaveValue("내 초안");
    expect(screen.getByRole("button", { name: "메모 저장" })).toHaveAttribute("aria-disabled", "true");
    await user.click(screen.getByRole("button", { name: "메모 저장" }));
    expect(updateMock).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "최신 메모 확인" }));
    await screen.findByText("서버 메모");
    expect(screen.getByRole("textbox")).toHaveValue("내 초안");
    await user.click(screen.getByRole("button", { name: "메모 저장" }));
    await screen.findByText("메모를 저장했습니다.");
    expect(updateMock).toHaveBeenLastCalledWith(42, "내 초안", 5);
  });

  it("최신 메모 조회 실패 후에도 초안과 충돌 상태를 유지해 재시도한다", async () => {
    fetchMock.mockResolvedValueOnce(DETAIL).mockRejectedValueOnce(new Error("연결 실패"))
      .mockResolvedValue({ ...DETAIL, memo: "다른 메모", version: 5 });
    updateMock.mockRejectedValueOnce(conflict());
    const user = userEvent.setup();
    renderMemo();
    await user.type(await open(user), "내 초안");
    await user.click(screen.getByRole("button", { name: "메모 저장" }));
    await user.click(await screen.findByRole("button", { name: "최신 메모 확인" }));
    await screen.findByText("연결 실패");
    expect(screen.getByRole("textbox")).toHaveValue("내 초안");
    expect(screen.getByRole("button", { name: "메모 저장" })).toHaveAttribute("aria-disabled", "true");
    await user.click(screen.getByRole("button", { name: "최신 메모 확인" }));
    await screen.findByText("다른 메모");
    expect(screen.getByRole("button", { name: "메모 저장" })).not.toHaveAttribute("aria-disabled");
  });

  it("저장 중에는 중복 저장과 편집 닫기를 막는다", async () => {
    let resolve!: (value: typeof DETAIL) => void;
    updateMock.mockImplementation(() => new Promise((done) => { resolve = done; }));
    const user = userEvent.setup();
    renderMemo();
    await open(user);
    await user.dblClick(screen.getByRole("button", { name: "메모 저장" }));
    await waitFor(() => expect(screen.getByRole("button", { name: LABEL + " 메모 쓰기" })).toHaveAttribute("aria-disabled", "true"));
    expect(screen.getByRole("textbox")).toHaveAttribute("readonly");
    await user.click(screen.getByRole("button", { name: LABEL + " 메모 쓰기" }));
    await user.click(screen.getByRole("button", { name: "메모 편집 취소" }));
    expect(screen.getByRole("textbox")).toBeVisible();
    expect(updateMock).toHaveBeenCalledTimes(1);
    resolve(DETAIL);
    await screen.findByText("메모를 저장했습니다.");
  });

  it("다시 열면 캐시 대신 새 조회가 끝난 뒤 최신 내용과 버전으로 편집한다", async () => {
    let resolve!: (value: { historyId: number; memo: string; version: number }) => void;
    fetchMock.mockResolvedValueOnce(DETAIL).mockImplementationOnce(() => new Promise((done) => { resolve = done; }));
    const user = userEvent.setup();
    renderMemo();
    await open(user);
    await user.click(screen.getByRole("button", { name: "메모 편집 취소" }));
    await user.click(screen.getByRole("button", { name: LABEL + " 메모 쓰기" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    resolve({ historyId: 42, memo: "최신 내용", version: 9 });
    expect(await screen.findByRole("textbox")).toHaveValue("최신 내용");
    await user.click(screen.getByRole("button", { name: "메모 저장" }));
    await waitFor(() => expect(updateMock).toHaveBeenCalledWith(42, "최신 내용", 9));
  });

  it("메모를 비워 저장하면 삭제 요청을 보낸다", async () => {
    fetchMock.mockResolvedValue({ ...DETAIL, memo: "기존 메모" });
    updateMock.mockResolvedValue({ ...DETAIL, version: 4 });
    const user = userEvent.setup();
    renderMemo("기존 메모");
    await user.clear(await open(user, "수정"));
    await user.click(screen.getByRole("button", { name: "메모 저장" }));
    await waitFor(() => expect(updateMock).toHaveBeenCalledWith(42, "", 3));
  });

  it("조회 실패를 재시도할 수 있다", async () => {
    fetchMock.mockRejectedValueOnce(new Error("조회 실패")).mockResolvedValue(DETAIL);
    const user = userEvent.setup();
    renderMemo();
    await user.click(screen.getByRole("button", { name: LABEL + " 메모 쓰기" }));
    await screen.findByText("조회 실패");
    await user.click(screen.getByRole("button", { name: "메모 다시 조회" }));
    expect(await screen.findByRole("textbox")).toHaveFocus();
  });

  it("메모의 HTML을 실행하지 않고 줄바꿈을 유지하는 텍스트로 표시한다", () => {
    const memo = "<img src=x onerror=alert(1)>\n다음 줄";
    const { container } = renderMemo(memo);
    expect(container.querySelector(".history-memo-text")?.textContent).toBe(memo);
    expect(container.querySelector("img")).toBeNull();
  });

  it("저장 응답을 기다리며 옮긴 초점을 유지한다", async () => {
    let resolve!: (value: typeof DETAIL) => void;
    updateMock.mockImplementation(() => new Promise((done) => { resolve = done; }));
    const user = userEvent.setup();
    renderWithProviders(<><HistoryMemo historyId={42} label={LABEL} memo={null} /><button>다른 작업</button></>);
    await open(user);
    await user.click(screen.getByRole("button", { name: "메모 저장" }));
    await user.click(screen.getByRole("button", { name: "다른 작업" }));
    resolve(DETAIL);
    await screen.findByText("메모를 저장했습니다.");
    expect(screen.getByRole("button", { name: "다른 작업" })).toHaveFocus();
  });
});
