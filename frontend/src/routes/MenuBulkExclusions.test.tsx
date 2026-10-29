import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import MenuBulkExclusions from "./MenuBulkExclusions";
import { batchUpdateExclusions, type MenuSummary } from "../api/menus";

vi.mock("../api/menus", () => ({ batchUpdateExclusions: vi.fn() }));
const save = vi.mocked(batchUpdateExclusions);
const menus: MenuSummary[] = [
  { id: 9, name: "김치찌개", isExcluded: false, weight: 3, categories: [], tags: [] },
  { id: 7, name: "비빔밥", isExcluded: true, weight: 4, categories: [], tags: [], pausedUntil: "2026-12-01T00:00:00" },
];
const close = vi.fn();
const load = vi.fn();
const reload = vi.fn();
function setup(overrides: Partial<Parameters<typeof MenuBulkExclusions>[0]> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const props = { menus, keyword: "", category: "", loading: false, loadError: false, hasNextPage: true,
    onLoadMore: load, onReload: reload, onClose: close, ...overrides };
  const tree = (items: MenuSummary[]) => <QueryClientProvider client={client}>
    <MenuBulkExclusions {...props} menus={items} />
  </QueryClientProvider>;
  return { client, ...render(tree(props.menus)), replaceMenus: (items: MenuSummary[]) => tree(items) };
}

beforeEach(() => { vi.resetAllMocks(); save.mockResolvedValue(undefined); });

it("선택한 ID만 한 요청으로 보내며 저장 후 관련 캐시와 선택·초점을 갱신한다", async () => {
  const user = userEvent.setup();
  const { client } = setup();
  client.setQueryData(["menus", "", ""], { menus });
  client.setQueryData(["menu", 9], menus[0]);
  client.setQueryData(["onboarding"], { needed: true });
  await user.click(screen.getByRole("checkbox", { name: "김치찌개 선택" }));
  await user.click(screen.getByRole("button", { name: "선택한 메뉴 추천 제외" }));
  const message = await screen.findByText("1개 메뉴를 추천에서 제외했습니다.");
  await waitFor(() => expect(message).toHaveFocus());
  expect(save).toHaveBeenCalledExactlyOnceWith([{ menuId: 9, excluded: true }]);
  expect(screen.getByRole("checkbox", { name: "김치찌개 선택" })).not.toBeChecked();
  for (const key of [["menus", "", ""], ["menu", 9], ["onboarding"]]) {
    expect(client.getQueryState(key)?.isInvalidated).toBe(true);
  }
});

it("불러온 메뉴만 선택하며 다음 페이지를 받아도 선택을 유지한다", async () => {
  const user = userEvent.setup();
  const view = setup();
  await user.click(screen.getByRole("button", { name: "불러온 2개 선택" }));
  await user.click(screen.getByRole("button", { name: "선택할 메뉴 더 보기" }));
  expect(load).toHaveBeenCalledTimes(1);
  const next = { ...menus[0], id: 3, name: "다음 메뉴" };
  view.rerender(view.replaceMenus([...menus, next]));
  expect(screen.getByRole("checkbox", { name: "김치찌개 선택" })).toBeChecked();
  expect(screen.getByRole("checkbox", { name: "다음 메뉴 선택" })).not.toBeChecked();
  await user.click(screen.getByRole("button", { name: "선택한 메뉴 추천 포함" }));
  await screen.findByText("2개 메뉴를 추천에 포함했습니다.");
  expect(save).toHaveBeenCalledWith([{ menuId: 9, excluded: false }, { menuId: 7, excluded: false }]);
});

it("저장 중 선택·중복 제출·종료·추가 조회를 막는다", async () => {
  const user = userEvent.setup();
  let finish!: () => void;
  save.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
  setup();
  await user.click(screen.getByRole("checkbox", { name: "김치찌개 선택" }));
  const apply = screen.getByRole("button", { name: "선택한 메뉴 추천 제외" });
  await user.click(apply);
  expect(screen.getByRole("checkbox", { name: "비빔밥 선택" })).toBeDisabled();
  await user.click(apply);
  await user.click(screen.getByRole("button", { name: "선택 종료" }));
  await user.click(screen.getByRole("button", { name: "선택 초기화" }));
  await user.click(screen.getByRole("button", { name: "선택할 메뉴 더 보기" }));
  expect(save).toHaveBeenCalledTimes(1);
  expect(close).not.toHaveBeenCalled();
  expect(load).not.toHaveBeenCalled();
  expect(screen.getByRole("checkbox", { name: "김치찌개 선택" })).toBeChecked();
  await act(async () => finish());
  await screen.findByText("1개 메뉴를 추천에서 제외했습니다.");
});

it("실패한 선택을 보존하고 같은 요청으로 재시도한다", async () => {
  const user = userEvent.setup();
  save.mockRejectedValueOnce(new Error("offline"));
  setup();
  await user.click(screen.getByRole("checkbox", { name: "비빔밥 선택" }));
  await user.click(screen.getByRole("button", { name: "선택한 메뉴 추천 포함" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("선택은 유지했습니다");
  expect(screen.getByRole("checkbox", { name: "비빔밥 선택" })).toBeChecked();
  await user.click(screen.getByRole("button", { name: "선택한 메뉴 추천 포함" }));
  await screen.findByText("1개 메뉴를 추천에 포함했습니다.");
  expect(save).toHaveBeenNthCalledWith(2, [{ menuId: 7, excluded: false }]);
});

it("선택한 메뉴가 재조회에서 사라지면 저장을 막고 명시적으로 초기화한다", async () => {
  const user = userEvent.setup();
  const view = setup();
  await user.click(screen.getByRole("checkbox", { name: "김치찌개 선택" }));
  view.rerender(view.replaceMenus([menus[1]]));
  expect(screen.getByRole("alert")).toHaveTextContent("목록에서 사라진 항목");
  await user.click(screen.getByRole("button", { name: "선택한 메뉴 추천 제외" }));
  expect(save).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "선택 초기화" }));
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

it("선택 상한 100개를 지키고 앞 100개 선택을 명시한다", async () => {
  const user = userEvent.setup();
  setup({ menus: Array.from({ length: 101 }, (_, index) => ({ ...menus[0], id: index + 1, name: `메뉴 ${index + 1}` })) });
  await user.click(screen.getByRole("button", { name: "불러온 메뉴 중 앞 100개 선택" }));
  expect(screen.getAllByRole("checkbox", { checked: true })).toHaveLength(100);
  expect(screen.getByRole("checkbox", { name: "메뉴 101 선택" })).toBeDisabled();
  await user.click(screen.getByRole("checkbox", { name: "메뉴 1 선택" }));
  await user.click(screen.getByRole("checkbox", { name: "메뉴 101 선택" }));
  await user.click(screen.getByRole("button", { name: "선택한 메뉴 추천 제외" }));
  await screen.findByText("100개 메뉴를 추천에서 제외했습니다.");
  expect(save.mock.calls[0][0]).toHaveLength(100);
  expect(save.mock.calls[0][0].some((entry) => entry.menuId === 1)).toBe(false);
});

it("조회 실패 중 저장을 막고 선택 목록 재조회를 제공한다", async () => {
  const user = userEvent.setup();
  setup({ loadError: true });
  expect(screen.getByRole("checkbox", { name: "김치찌개 선택" })).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "불러온 2개 선택" }));
  await user.click(screen.getByRole("button", { name: "선택한 메뉴 추천 제외" }));
  expect(save).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "선택 목록 다시 조회" }));
  expect(reload).toHaveBeenCalledTimes(1);
});
