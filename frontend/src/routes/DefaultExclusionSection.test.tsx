import { beforeEach, expect, it, vi } from "vitest";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import DefaultExclusionSection from "./DefaultExclusionSection";
import { fetchAllTags } from "../api/tags";
import { fetchDefaultExcludedTagIds, updateDefaultExcludedTagIds } from "../api/pickPreferences";

vi.mock("../api/tags", () => ({ fetchAllTags: vi.fn() }));
vi.mock("../api/pickPreferences", () => ({
  fetchDefaultExcludedTagIds: vi.fn(), updateDefaultExcludedTagIds: vi.fn(),
}));
const tags = [
  { id: 9, name: "땅콩", version: 0, createdAt: "2026-10-01" },
  { id: 11, name: "해산물", version: 0, createdAt: "2026-10-01" },
  { id: 13, name: "VEGAN", version: 0, createdAt: "2026-10-01" },
];
const fetchTags = vi.mocked(fetchAllTags);
const fetchPreferences = vi.mocked(fetchDefaultExcludedTagIds);
const update = vi.mocked(updateDefaultExcludedTagIds);
const options = () => within(screen.getByRole("group", { name: "기본 제외할 태그" }));

function renderSection() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><DefaultExclusionSection /></QueryClientProvider>);
  return client;
}

beforeEach(() => {
  vi.resetAllMocks();
  fetchTags.mockResolvedValue(tags);
  fetchPreferences.mockResolvedValue([9]);
  update.mockImplementation(async (ids) => ids);
});

it("검색으로 가려진 선택을 유지하고 공백·대소문자를 무시하며 검색 지우기 후 초점을 돌린다", async () => {
  const user = userEvent.setup();
  renderSection();
  await waitFor(() => expect(options().getByLabelText("땅콩")).toBeChecked());
  const search = screen.getByRole("searchbox", { name: "기본 제외 태그 검색" });
  await user.type(search, "해산");
  expect(options().queryByLabelText("땅콩")).not.toBeInTheDocument();
  await user.click(options().getByLabelText("해산물"));
  await user.click(screen.getByRole("button", { name: "기본 제외 저장" }));
  await waitFor(() => expect(update).toHaveBeenCalledWith([9, 11]));
  await user.clear(search);
  await user.type(search, " vegan ");
  expect(options().getByLabelText("VEGAN")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "검색 지우기" }));
  expect(search).toHaveFocus();
  expect(options().getByLabelText("땅콩")).toBeChecked();
  expect(options().getByLabelText("해산물")).toBeChecked();
  expect(fetchTags).toHaveBeenCalledTimes(1);
});

it("선택한 태그만 보다가 마지막 선택을 해제하면 빈 상태와 초점을 유지하며 빈 목록도 저장한다", async () => {
  const user = userEvent.setup();
  renderSection();
  await waitFor(() => expect(options().getByLabelText("땅콩")).toBeChecked());
  const filter = screen.getByRole("checkbox", { name: "선택한 태그만 보기" });
  await user.click(filter);
  expect(options().getAllByRole("checkbox")).toHaveLength(1);
  await user.click(options().getByLabelText("땅콩"));
  expect(filter).toHaveFocus();
  expect(screen.getByText(/선택한 태그가 없어요/)).toBeVisible();
  await user.click(screen.getByRole("button", { name: "기본 제외 저장" }));
  await waitFor(() => expect(update).toHaveBeenCalledWith([]));
});

it("설정을 읽기 전에는 빈 선택을 저장하지 않는다", async () => {
  const user = userEvent.setup();
  let finish!: (ids: number[]) => void;
  fetchPreferences.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
  renderSection();
  await screen.findByRole("group", { name: "기본 제외할 태그" });
  expect(options().getByLabelText("땅콩")).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "기본 제외 저장" }));
  expect(update).not.toHaveBeenCalled();
  await act(async () => finish([9]));
  await waitFor(() => expect(options().getByLabelText("땅콩")).toBeChecked());
});

it("조회 실패 시 저장을 막고 다시 불러온 설정을 표시한다", async () => {
  const user = userEvent.setup();
  fetchPreferences.mockRejectedValueOnce(new Error("offline"));
  renderSection();
  expect(await screen.findByRole("alert")).toHaveTextContent("불러오지 못했습니다");
  await user.click(screen.getByRole("button", { name: "기본 제외 저장" }));
  expect(update).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "기본 제외 다시 불러오기" }));
  await waitFor(() => expect(options().getByLabelText("땅콩")).toBeChecked());
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

it("저장 중 수정·중복 제출을 막고 응답 ID 목록을 캐시에 반영하며 다시 수정하면 저장 표시를 지운다", async () => {
  const user = userEvent.setup();
  let finish!: (ids: number[]) => void;
  update.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
  const client = renderSection();
  await waitFor(() => expect(options().getByLabelText("땅콩")).toBeChecked());
  await user.click(options().getByLabelText("해산물"));
  const save = screen.getByRole("button", { name: "기본 제외 저장" });
  await user.click(save);
  expect(options().getByLabelText("해산물")).toBeDisabled();
  await user.click(save);
  await user.click(screen.getByRole("button", { name: "변경 취소" }));
  expect(options().getByLabelText("해산물")).toBeChecked();
  expect(update).toHaveBeenCalledTimes(1);
  await act(async () => finish([9, 11]));
  expect(await screen.findByText("기본 제외 설정을 저장했습니다.")).toBeVisible();
  expect(client.getQueryData(["pick-preferences"])).toEqual([9, 11]);
  await user.click(options().getByLabelText("해산물"));
  expect(screen.queryByText("기본 제외 설정을 저장했습니다.")).not.toBeInTheDocument();
  expect(screen.getByText("아직 저장하지 않은 변경이 있어요.")).toBeVisible();
});

it("저장 실패 후 검색과 선택을 보존하고 재시도한다", async () => {
  const user = userEvent.setup();
  update.mockRejectedValueOnce(new Error("offline"));
  renderSection();
  await waitFor(() => expect(options().getByLabelText("땅콩")).toBeChecked());
  await user.type(screen.getByRole("searchbox"), "해산");
  await user.click(options().getByLabelText("해산물"));
  await user.click(screen.getByRole("button", { name: "기본 제외 저장" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("저장하지 못했습니다");
  expect(screen.getByRole("searchbox")).toHaveValue("해산");
  expect(options().getByLabelText("해산물")).toBeChecked();
  await user.click(screen.getByRole("button", { name: "기본 제외 저장" }));
  await screen.findByText("기본 제외 설정을 저장했습니다.");
  expect(update).toHaveBeenNthCalledWith(2, [9, 11]);
});

it("백그라운드 재조회가 초안을 덮지 않고 변경 취소는 최신 저장값으로 되돌린다", async () => {
  const user = userEvent.setup();
  const client = renderSection();
  await waitFor(() => expect(options().getByLabelText("땅콩")).toBeChecked());
  await user.click(options().getByLabelText("해산물"));
  act(() => { client.setQueryData(["pick-preferences"], [13]); });
  expect(options().getByLabelText("해산물")).toBeChecked();
  expect(options().getByLabelText("VEGAN")).not.toBeChecked();
  await user.click(screen.getByRole("button", { name: "변경 취소" }));
  expect(options().getByLabelText("VEGAN")).toBeChecked();
  expect(options().getByLabelText("땅콩")).not.toBeChecked();
  expect(screen.getByRole("button", { name: "기본 제외 저장" })).toHaveFocus();
  expect(update).not.toHaveBeenCalled();
});
