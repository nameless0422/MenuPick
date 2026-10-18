import { beforeEach, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError } from "axios";
import { renderWithProviders } from "../test/renderWithProviders";
import EditPickPreset from "./EditPickPreset";
import { fetchPickPreset, updatePickPreset, type PickPreset } from "../api/pickPresets";
import { fetchAllTags } from "../api/tags";

vi.mock("../api/pickPresets", async original => ({
  ...await original<typeof import("../api/pickPresets")>(), fetchPickPreset: vi.fn(), updatePickPreset: vi.fn(),
}));
vi.mock("../api/tags", () => ({ fetchAllTags: vi.fn() }));
const get = vi.mocked(fetchPickPreset);
const update = vi.mocked(updatePickPreset);
const tags = vi.mocked(fetchAllTags);
const original: PickPreset = { id: 1, name: "회사 점심", categories: ["한식"], includeTagIds: [7],
  additionalExcludeTagIds: [9], maxDistance: 500, needsReview: false, version: 8,
  createdAt: "2026-01-01T00:00:00", updatedAt: "2026-01-01T00:00:00" };
const error = (code: string) => new AxiosError("실패", undefined, undefined, undefined, {
  status: 409, data: { errorCode: code, message: "먼저 수정됐습니다." }, headers: {}, statusText: "Conflict", config: {} as never,
});
beforeEach(() => {
  get.mockReset().mockResolvedValue(original);
  update.mockReset().mockResolvedValue({ ...original, version: 9 });
  tags.mockReset().mockResolvedValue([
    { id: 7, name: "혼밥", version: 0, createdAt: "" }, { id: 9, name: "매움", version: 0, createdAt: "" },
  ]);
});
const mount = (close = vi.fn()) => { renderWithProviders(<EditPickPreset id={1} onClose={close} />); return close; };
const save = () => screen.getByRole("button", { name: "수정 저장" });

it("최신 상세의 버전과 편집한 전체 조건을 저장하며 추가 제외와 기본 제외를 구분한다", async () => {
  const user = userEvent.setup();
  const close = mount();
  const name = await screen.findByLabelText("수정할 빠른 픽 이름");
  expect(get).toHaveBeenCalledWith(1);
  expect(screen.getByRole("heading", { name: "빠른 픽 조건 수정" })).toHaveFocus();
  await user.clear(name); await user.type(name, " 주말 점심 ");
  await user.clear(screen.getByLabelText("수정할 카테고리"));
  await user.type(screen.getByLabelText("수정할 카테고리"), " 중식\n직접 분류\n중식 ");
  await user.selectOptions(screen.getByLabelText("수정할 거리 조건"), "1000");
  await user.selectOptions(await screen.findByLabelText("혼밥"), "exclude");
  await user.selectOptions(screen.getByLabelText("매움"), "include");
  await user.click(save());
  await waitFor(() => expect(update).toHaveBeenCalledWith(1, {
    name: "주말 점심", categories: ["중식", "직접 분류"], includeTagIds: [9], additionalExcludeTagIds: [7],
    maxDistance: 1000, version: 8, acknowledgeRemovedTags: false,
  }));
  await waitFor(() => expect(close).toHaveBeenCalledOnce());
});

it("삭제 태그 검토 상태는 전체 조건 확인 동의 없이 저장하지 않는다", async () => {
  const user = userEvent.setup();
  get.mockResolvedValue({ ...original, needsReview: true });
  mount();
  await screen.findByLabelText("수정할 빠른 픽 이름");
  await screen.findByLabelText("혼밥");
  await user.click(save()); expect(update).not.toHaveBeenCalled();
  await user.click(screen.getByRole("checkbox"));
  await user.click(save());
  await waitFor(() => expect(update).toHaveBeenCalledWith(1, expect.objectContaining({ acknowledgeRemovedTags: true, version: 8 })));
});

it("버전 충돌 후 초안과 시작 버전을 유지하고 재저장을 막는다", async () => {
  const user = userEvent.setup();
  update.mockRejectedValue(error("CONCURRENT_MODIFICATION"));
  mount();
  const name = await screen.findByLabelText("수정할 빠른 픽 이름");
  await user.clear(name); await user.type(name, "유지할 초안");
  await screen.findByLabelText("혼밥");
  await user.click(save());
  await screen.findByText(/입력 내용은 유지했습니다/);
  expect(name).toHaveValue("유지할 초안");
  await user.click(save());
  expect(update).toHaveBeenCalledOnce();
});

it("이름 중복 오류 후 이름을 고쳐 같은 버전으로 다시 저장할 수 있다", async () => {
  const user = userEvent.setup();
  update.mockRejectedValueOnce(error("PICK_PRESET_NAME_DUPLICATE")).mockResolvedValueOnce({ ...original, version: 9 });
  mount(); await screen.findByLabelText("혼밥");
  await user.click(save()); await screen.findByRole("alert");
  await user.type(screen.getByLabelText("수정할 빠른 픽 이름"), " 수정");
  await user.click(save());
  await waitFor(() => expect(update).toHaveBeenCalledTimes(2));
  expect(update.mock.calls[1][1].version).toBe(8);
});

it("저장 중 중복 제출과 취소를 막는다", async () => {
  const user = userEvent.setup();
  update.mockImplementation(() => new Promise(() => {}));
  const close = mount(); await screen.findByLabelText("혼밥");
  await user.click(save());
  await user.click(screen.getByRole("button", { name: "수정 저장 중…" }));
  await user.click(screen.getByRole("button", { name: "수정 취소" }));
  expect(update).toHaveBeenCalledOnce(); expect(close).not.toHaveBeenCalled();
});

it("태그 조회 실패 시 조건을 잃지 않고 재시도한다", async () => {
  const user = userEvent.setup();
  tags.mockRejectedValueOnce(new Error("태그 오류")).mockResolvedValue([]);
  mount(); await screen.findByText("태그 오류");
  await user.click(save()); expect(update).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "태그 다시 조회" }));
  const missing = await screen.findByLabelText("삭제되었거나 조회되지 않은 태그 #7");
  expect(missing).toHaveValue("include");
  await user.selectOptions(missing, "none");
  await user.click(save());
  await waitFor(() => expect(update).toHaveBeenCalledWith(1, expect.objectContaining({ includeTagIds: [], additionalExcludeTagIds: [9] })));
});

it("카테고리 길이를 검증한다", async () => {
  const user = userEvent.setup();
  mount(); await screen.findByLabelText("혼밥");
  await user.clear(screen.getByLabelText("수정할 카테고리"));
  await user.type(screen.getByLabelText("수정할 카테고리"), "가".repeat(21));
  await user.click(save()); expect(update).not.toHaveBeenCalled();
  expect(screen.getByRole("alert")).toHaveTextContent("카테고리는 20자 이하");
});

it("상세 조회 실패 후 다시 읽고 조회 중에는 취소할 수 있다", async () => {
  const user = userEvent.setup();
  get.mockRejectedValueOnce(new Error("상세 오류")).mockImplementationOnce(() => new Promise(() => {}));
  const close = mount();
  await screen.findByText("상세 오류");
  await user.click(screen.getByRole("button", { name: "조건 다시 조회" }));
  await user.click(screen.getByRole("button", { name: "수정 취소" }));
  expect(close).toHaveBeenCalledOnce();
  expect(update).not.toHaveBeenCalled();
});
