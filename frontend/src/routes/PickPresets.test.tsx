import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import PickPresets from "./PickPresets";
import {
  deletePickPreset,
  executePickPreset,
  fetchPickPresets,
  type PickPreset,
} from "../api/pickPresets";

vi.mock("../api/pickPresets", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/pickPresets")>()),
  fetchPickPresets: vi.fn(),
  executePickPreset: vi.fn(),
  deletePickPreset: vi.fn(),
}));

const fetchMock = vi.mocked(fetchPickPresets);
const executeMock = vi.mocked(executePickPreset);
const deleteMock = vi.mocked(deletePickPreset);

const preset = (overrides: Partial<PickPreset> = {}): PickPreset => ({
  id: 1,
  name: "회사 점심",
  categories: ["한식"],
  includeTagIds: [7],
  additionalExcludeTagIds: [9],
  maxDistance: null,
  needsReview: false,
  version: 3,
  createdAt: "2026-01-01T00:00:00",
  updatedAt: "2026-01-01T00:00:00",
  ...overrides,
});

const TAG_NAMES: Record<number, string> = { 7: "혼밥", 9: "매움" };

function render(onResult = vi.fn()) {
  renderWithProviders(
    <PickPresets tagNameOf={(id) => TAG_NAMES[id] ?? `#${id}`} onResult={onResult} />,
  );
  return onResult;
}

beforeEach(() => {
  fetchMock.mockReset();
  executeMock.mockReset();
  deleteMock.mockReset();
  fetchMock.mockResolvedValue({ presets: [preset()], limit: 10 });
  executeMock.mockResolvedValue({
    pick: { historyId: 1, menu: { id: 1, name: "김치찌개" }, restaurants: [], reasons: [] } as never,
    appliedFilters: {
      categories: ["한식"],
      includeTagIds: [7],
      effectiveExcludeTagIds: [9, 11],
      maxDistance: null,
    },
  });
  deleteMock.mockResolvedValue(undefined);
  vi.stubGlobal("navigator", {
    ...navigator,
    geolocation: {
      getCurrentPosition: vi.fn((ok: PositionCallback) =>
        ok({ coords: { latitude: 37.5, longitude: 127.0 } } as GeolocationPosition),
      ),
    },
  });
});

describe("빠른 픽", () => {
  it("저장해 둔 프리셋을 보여준다", async () => {
    render();
    expect(await screen.findByRole("button", { name: /회사 점심/ })).toBeInTheDocument();
  });

  it("저장한 것이 없으면 만드는 법을 알려준다", async () => {
    fetchMock.mockResolvedValue({ presets: [], limit: 10 });
    render();
    expect(await screen.findByText(/저장해 둔 빠른 픽이 없어요/)).toBeInTheDocument();
  });

  /**
   * 고르는 것만으로 돌면 무엇으로 뽑았는지 모른 채 결과만 받는다 — 특히 그 사이 기본 제외가
   * 바뀌었을 때 사용자가 알아챌 자리가 없다.
   */
  it("고르기만 해서는 실행되지 않는다 — 조건을 먼저 보여준다", async () => {
    const user = userEvent.setup();
    render();

    await user.click(await screen.findByRole("button", { name: /회사 점심/ }));

    expect(executeMock).not.toHaveBeenCalled();
    expect(screen.getByText("혼밥")).toBeInTheDocument();
    expect(screen.getByText("매움")).toBeInTheDocument();
    expect(screen.getByText(/기본 제외 태그가 함께 적용/)).toBeInTheDocument();
  });

  it("실행 버튼을 눌러야 저장된 버전으로 실행한다", async () => {
    const user = userEvent.setup();
    const onResult = render();

    await user.click(await screen.findByRole("button", { name: /회사 점심/ }));
    await user.click(screen.getByRole("button", { name: /이 조건으로 뽑기/ }));

    await waitFor(() => expect(executeMock).toHaveBeenCalledWith(1, { version: 3 }));
    await waitFor(() => expect(onResult).toHaveBeenCalled());
  });

  /** 좌표를 저장하지 않으므로 실행할 때마다 새로 받는다. */
  it("거리 조건이 있으면 실행 시점에 위치를 받아 함께 보낸다", async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValue({ presets: [preset({ maxDistance: 500 })], limit: 10 });
    render();

    await user.click(await screen.findByRole("button", { name: /회사 점심/ }));
    await user.click(screen.getByRole("button", { name: /이 조건으로 뽑기/ }));

    await waitFor(() =>
      expect(executeMock).toHaveBeenCalledWith(1, {
        version: 3,
        latitude: 37.5,
        longitude: 127.0,
      }),
    );
  });

  /**
   * 위치를 못 받으면 거리 조건을 빼고 그냥 뽑지 않는다 — 사용자는 "가까운 곳"을 기대했는데
   * 전혀 다른 결과를 받게 된다.
   */
  it("위치를 거부하면 실행하지 않고 이유를 알린다", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("navigator", {
      ...navigator,
      geolocation: {
        getCurrentPosition: vi.fn((_ok: PositionCallback, fail: PositionErrorCallback) =>
          fail({ code: 1 } as GeolocationPositionError),
        ),
      },
    });
    fetchMock.mockResolvedValue({ presets: [preset({ maxDistance: 500 })], limit: 10 });
    render();

    await user.click(await screen.findByRole("button", { name: /회사 점심/ }));
    await user.click(screen.getByRole("button", { name: /이 조건으로 뽑기/ }));

    expect(await screen.findByText(/위치를 가져오지 못해 실행하지 않았어요/)).toBeInTheDocument();
    expect(executeMock).not.toHaveBeenCalled();
  });

  /** 이 파일의 핵심 — 조건이 줄어든 채로 도는 것을 화면에서도 막는다. */
  it("태그가 삭제된 프리셋은 경고를 띄우고 실행을 막는다", async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValue({
      presets: [preset({ needsReview: true, additionalExcludeTagIds: [] })],
      limit: 10,
    });
    render();

    await user.click(await screen.findByRole("button", { name: /회사 점심/ }));

    expect(screen.getByText(/태그가 삭제됐어요/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /이 조건으로 뽑기/ }));
    expect(executeMock).not.toHaveBeenCalled();
  });

  it("삭제는 확인을 거친 뒤 버전과 함께 보낸다", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    render();

    await user.click(await screen.findByRole("button", { name: /회사 점심/ }));
    await user.click(screen.getByRole("button", { name: "회사 점심 삭제" }));

    expect(confirmSpy).toHaveBeenCalledWith("'회사 점심' 빠른 픽을 삭제할까요?");
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(1, 3));
    confirmSpy.mockRestore();
  });

  it("삭제를 취소하면 지우지 않는다", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    render();

    await user.click(await screen.findByRole("button", { name: /회사 점심/ }));
    await user.click(screen.getByRole("button", { name: "회사 점심 삭제" }));

    expect(deleteMock).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });
});
