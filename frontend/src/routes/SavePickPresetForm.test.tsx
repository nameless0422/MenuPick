import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import SavePickPresetForm from "./SavePickPresetForm";
import { createPickPreset } from "../api/pickPresets";

vi.mock("../api/pickPresets", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/pickPresets")>()),
  createPickPreset: vi.fn(),
}));

const createMock = vi.mocked(createPickPreset);

beforeEach(() => {
  createMock.mockReset();
  createMock.mockResolvedValue({} as never);
});

function render(props: Partial<React.ComponentProps<typeof SavePickPresetForm>> = {}) {
  renderWithProviders(
    <SavePickPresetForm
      categories={["한식"]}
      includeTagIds={[7]}
      excludeTagIds={[9]}
      defaultExcludedTagIds={[]}
      maxDistance={null}
      {...props}
    />,
  );
}

async function openForm(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "현재 조건 저장" }));
}

describe("현재 조건을 빠른 픽으로 저장", () => {
  it("이름을 넣어 저장하면 지금 조건이 그대로 실린다", async () => {
    const user = userEvent.setup();
    render();
    await openForm(user);

    await user.type(screen.getByRole("textbox", { name: "빠른 픽 이름" }), "회사 점심");
    await user.click(screen.getByRole("button", { name: "저장" }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith({
        name: "회사 점심",
        categories: ["한식"],
        includeTagIds: [7],
        additionalExcludeTagIds: [9],
        maxDistance: null,
      }),
    );
  });

  /**
   * 빠른 픽의 추가 제외는 `현재 제외 − 기본 제외`다. 기본 제외를 그대로 복사하면 나중에
   * 사용자가 기본 제외를 바꿔도 옛 프리셋이 옛 값을 붙들고 있는 것처럼 보인다.
   */
  it("기본 제외 태그는 추가 제외에 넣지 않는다", async () => {
    const user = userEvent.setup();
    render({ excludeTagIds: [9, 11], defaultExcludedTagIds: [11] });
    await openForm(user);

    await user.type(screen.getByRole("textbox", { name: "빠른 픽 이름" }), "점심");
    await user.click(screen.getByRole("button", { name: "저장" }));

    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(createMock.mock.calls[0][0].additionalExcludeTagIds).toEqual([9]);
  });

  /**
   * 이 테스트가 이 폼의 핵심이다. 사용자가 이번 픽에서만 기본 제외를 빼 뒀다면 그 해제는
   * 저장되지 않는데, 조용히 저장하면 다음 실행에서 그 태그가 되살아나 뽑히지 않는다.
   */
  it("기본 제외를 빼 둔 상태면 저장되지 않는다고 먼저 알린다", async () => {
    const user = userEvent.setup();
    render({ excludeTagIds: [], defaultExcludedTagIds: [11, 12] });
    await openForm(user);

    expect(screen.getByText(/빠른 픽은 기본 제외를 해제할 수 없습니다/)).toBeInTheDocument();
    expect(screen.getByText(/2개를 빼 두셨는데/)).toBeInTheDocument();
  });

  it("기본 제외를 모두 켜 둔 상태면 그 경고를 띄우지 않는다", async () => {
    const user = userEvent.setup();
    render({ excludeTagIds: [11], defaultExcludedTagIds: [11] });
    await openForm(user);

    expect(screen.queryByText(/기본 제외를 해제할 수 없습니다/)).toBeNull();
  });

  /** 프리셋 거리는 네 단계뿐이라, 수동 화면의 자유 값을 그대로 저장할 수 없다. */
  it("자유 거리 값은 가장 가까운 단계로 맞추고 그 사실을 알린다", async () => {
    const user = userEvent.setup();
    render({ maxDistance: 800 });
    await openForm(user);

    expect(screen.getByText(/1000m 이내/)).toBeInTheDocument();
    expect(screen.getByText(/가장 가까운 값으로 맞춥니다/)).toBeInTheDocument();

    await user.type(screen.getByRole("textbox", { name: "빠른 픽 이름" }), "가까운 곳");
    await user.click(screen.getByRole("button", { name: "저장" }));

    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(createMock.mock.calls[0][0].maxDistance).toBe(1000);
  });

  it("이름이 비면 저장하지 않는다", async () => {
    const user = userEvent.setup();
    render();
    await openForm(user);

    await user.click(screen.getByRole("button", { name: "저장" }));

    expect(createMock).not.toHaveBeenCalled();
  });
});
