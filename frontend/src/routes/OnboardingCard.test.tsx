import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import OnboardingCard from "./OnboardingCard";
import { fetchOnboardingStatus, type OnboardingStatus } from "../api/onboarding";
import { batchUpdateExclusions } from "../api/menus";

vi.mock("../api/onboarding", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/onboarding")>()),
  fetchOnboardingStatus: vi.fn(),
}));
vi.mock("../api/menus", () => ({ batchUpdateExclusions: vi.fn() }));

const statusMock = vi.mocked(fetchOnboardingStatus);
const saveMock = vi.mocked(batchUpdateExclusions);

const status = (overrides: Partial<OnboardingStatus> = {}): OnboardingStatus => ({
  needed: true,
  menus: [
    { id: 1, name: "김치찌개", categories: ["한식"] },
    { id: 2, name: "마라탕", categories: ["중식"] },
    { id: 3, name: "초밥", categories: ["일식"] },
  ],
  ...overrides,
});

beforeEach(() => {
  window.localStorage.clear();
  statusMock.mockReset();
  saveMock.mockReset();
  statusMock.mockResolvedValue(status());
  saveMock.mockResolvedValue(undefined);
});

describe("첫 사용자 안내", () => {
  it("안 먹는 것을 빼라고 안내하고 기본 메뉴를 보여준다", async () => {
    renderWithProviders(<OnboardingCard />);

    expect(await screen.findByRole("heading", { name: /안 드시는 것부터/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "마라탕" })).toBeInTheDocument();
  });

  /** 이미 쓰고 있는 사용자를 다시 붙잡으면 안 된다. 판정은 서버가 한다. */
  it("서버가 필요 없다고 하면 아무것도 그리지 않는다", async () => {
    statusMock.mockResolvedValue(status({ needed: false }));
    const { container } = renderWithProviders(<OnboardingCard />);

    await waitFor(() => expect(statusMock).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("고른 메뉴만 한 번에 제외로 저장한다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<OnboardingCard />);

    await user.click(await screen.findByRole("button", { name: "마라탕" }));
    await user.click(screen.getByRole("button", { name: "초밥" }));
    await user.click(screen.getByRole("button", { name: "2개 빼고 시작하기" }));

    await waitFor(() =>
      expect(saveMock).toHaveBeenCalledWith([
        { menuId: 2, excluded: true },
        { menuId: 3, excluded: true },
      ]),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("2개를 뺐어요");
  });

  /** 빈 목록은 서버가 400으로 거절한다(@NotEmpty). 보낼 것이 없으면 부르지 않는다. */
  it("아무것도 빼지 않으면 서버를 부르지 않고 넘어간다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<OnboardingCard />);

    await user.click(await screen.findByRole("button", { name: "그대로 시작하기" }));

    expect(saveMock).not.toHaveBeenCalled();
    expect(await screen.findByRole("status")).toHaveTextContent("바로 뽑아볼까요");
  });

  it("건너뛰면 카드가 닫히고 다시 불러오지 않는다", async () => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<OnboardingCard />);

    await user.click(await screen.findByRole("button", { name: "다음에 할게요" }));

    expect(container).toBeEmptyDOMElement();
    expect(window.localStorage.getItem("menupick.onboarding.skipped")).toBe("1");
  });

  it("이미 건너뛴 브라우저에서는 상태를 묻지도 않는다", async () => {
    window.localStorage.setItem("menupick.onboarding.skipped", "1");
    const { container } = renderWithProviders(<OnboardingCard />);

    await waitFor(() => expect(container).toBeEmptyDOMElement());
    expect(statusMock).not.toHaveBeenCalled();
  });

  it("저장에 실패하면 이유를 보여주고 고른 것을 유지한다", async () => {
    const user = userEvent.setup();
    saveMock.mockRejectedValue(new Error("일시적인 오류"));
    renderWithProviders(<OnboardingCard />);

    await user.click(await screen.findByRole("button", { name: "마라탕" }));
    await user.click(screen.getByRole("button", { name: "1개 빼고 시작하기" }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /마라탕/ })).toHaveAttribute("aria-pressed", "true");
  });

  /** 첫 화면을 오류로 맞이하게 하지 않는다 — 안내는 곁다리이고 뽑기가 본 동작이다. */
  it("상태를 못 불러오면 조용히 아무것도 그리지 않는다", async () => {
    statusMock.mockRejectedValue(new Error("네트워크"));
    const { container } = renderWithProviders(<OnboardingCard />);

    await waitFor(() => expect(statusMock).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
