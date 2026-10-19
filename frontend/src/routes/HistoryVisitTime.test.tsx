import { beforeEach, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError } from "axios";
import { renderWithProviders } from "../test/renderWithProviders";
import HistoryVisitTime from "./HistoryVisitTime";
import { fetchHistoryVisitTime, updateHistoryVisitTime } from "../api/history";
vi.mock("../api/history", () => ({ fetchHistoryVisitTime: vi.fn(), updateHistoryVisitTime: vi.fn() }));
const get = vi.mocked(fetchHistoryVisitTime);
const put = vi.mocked(updateHistoryVisitTime);
const data = { historyId: 42, visitedAt: "2026-10-20T12:00:00", earliestVisitAt: "2026-10-01T00:00:00",
  latestVisitAt: "2026-10-20T12:30:00", version: 8 };
beforeEach(() => { get.mockReset().mockResolvedValue(data); put.mockReset().mockResolvedValue({ ...data, version: 9 }); });
const mount = () => renderWithProviders(<HistoryVisitTime historyId={42} label="국수" />);
const opener = () => screen.getByRole("button", { name: "국수 방문 일시 수정" });
const input = () => screen.getByLabelText("방문한 날짜와 시각 (한국 시간)");
const save = () => screen.getByRole("button", { name: "방문 일시 저장" });

it("열 때 최신 상태를 조회하고 한국 시간 문자열과 시작 버전을 저장한다", async () => {
  const user = userEvent.setup(); mount(); expect(get).not.toHaveBeenCalled();
  await user.click(opener()); await screen.findByLabelText("방문한 날짜와 시각 (한국 시간)");
  expect(input()).toHaveFocus(); expect(input()).toHaveAttribute("min", data.earliestVisitAt);
  expect(input()).toHaveAttribute("max", data.latestVisitAt);
  await user.clear(input()); await user.type(input(), "2026-10-19T15:30:00");
  await user.click(save());
  await waitFor(() => expect(put).toHaveBeenCalledWith(42, "2026-10-19T15:30:00", 8));
  await screen.findByText("방문 일시를 수정했습니다."); expect(opener()).toHaveFocus();
});

it("충돌 후 초안을 보존하고 취소 후 다시 열어 최신 버전을 받는다", async () => {
  const user = userEvent.setup();
  put.mockRejectedValue(new AxiosError("conflict", undefined, undefined, undefined,
    { data: { errorCode: "CONCURRENT_MODIFICATION", message: "충돌" }, status: 409, headers: {}, statusText: "", config: {} as never }));
  mount(); await user.click(opener()); await screen.findByLabelText("방문한 날짜와 시각 (한국 시간)");
  await user.click(save()); await screen.findByText(/입력한 일시는 유지했습니다/);
  expect(input()).toHaveValue(data.visitedAt.slice(0,16));
  await user.click(save()); expect(put).toHaveBeenCalledOnce();
  await user.click(screen.getByRole("button", { name: "방문 일시 편집 취소" }));
  get.mockResolvedValue({ ...data, version: 10 }); put.mockResolvedValue({ ...data, version: 11 });
  await user.click(opener()); await screen.findByLabelText("방문한 날짜와 시각 (한국 시간)");
  await user.click(save()); await waitFor(() => expect(put).toHaveBeenLastCalledWith(42, data.visitedAt, 10));
});

it("저장 중 중복 제출·닫기·취소를 막는다", async () => {
  const user = userEvent.setup(); put.mockImplementation(() => new Promise(() => {}));
  mount(); await user.click(opener()); await screen.findByLabelText("방문한 날짜와 시각 (한국 시간)");
  await user.click(save()); await user.click(screen.getByRole("button", { name: "방문 일시 저장 중…" }));
  await user.click(opener()); await user.click(screen.getByRole("button", { name: "방문 일시 편집 취소" }));
  expect(put).toHaveBeenCalledOnce(); expect(input()).toBeInTheDocument();
});

it("조회 실패를 재시도하거나 조회 중 취소할 수 있다", async () => {
  const user = userEvent.setup(); get.mockRejectedValueOnce(new Error("조회 실패"));
  mount(); await user.click(opener()); await screen.findByText("조회 실패");
  await user.click(screen.getByRole("button", { name: "방문 일시 다시 조회" }));
  await screen.findByLabelText("방문한 날짜와 시각 (한국 시간)");
  await user.click(screen.getByRole("button", { name: "방문 일시 편집 취소" }));
  expect(opener()).toHaveFocus(); expect(put).not.toHaveBeenCalled();
});
