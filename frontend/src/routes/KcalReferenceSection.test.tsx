import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import { fetchKcalReference, updateKcalReference } from "../api/nutrition";
import KcalReferenceSection from "./KcalReferenceSection";

vi.mock("../api/nutrition", () => ({ fetchKcalReference: vi.fn(), updateKcalReference: vi.fn() }));
const fetchMock = vi.mocked(fetchKcalReference);
const updateMock = vi.mocked(updateKcalReference);

beforeEach(() => {
  fetchMock.mockReset().mockResolvedValue({ dailyKcal: null });
  updateMock.mockReset().mockImplementation(async (dailyKcal) => ({ dailyKcal }));
});

const input = () => screen.getByLabelText("하루 기준 열량 (kcal)");
const save = () => screen.getByRole("button", { name: "저장" });

describe("열량 그래프 기준선", () => {
  it("범위 밖 값은 이유를 알리고 저장하지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<KcalReferenceSection />);
    await waitFor(() => expect(input()).toBeEnabled());

    await user.type(input(), "400");
    expect(screen.getByText(/500~6,000kcal 사이/)).toBeInTheDocument();
    await user.click(save());
    expect(updateMock).not.toHaveBeenCalled();
  });

  it("값을 저장하고, 저장한 기준선은 지울 수 있다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<KcalReferenceSection />);
    await waitFor(() => expect(input()).toBeEnabled());

    await user.type(input(), "2000");
    await user.click(save());
    expect(updateMock).toHaveBeenCalledWith(2000);
    expect(await screen.findByRole("status")).toHaveTextContent("2,000kcal로 저장했어요");

    await user.click(screen.getByRole("button", { name: "기준선 지우기" }));
    expect(updateMock).toHaveBeenLastCalledWith(null);
    expect(await screen.findByText("기준선을 지웠어요.")).toBeInTheDocument();
    expect(input()).toHaveValue(null);
  });

  it("저장된 값을 불러와 보이고, 바꾸지 않으면 저장 버튼이 잠겨 있다", async () => {
    fetchMock.mockResolvedValue({ dailyKcal: 1800 });
    renderWithProviders(<KcalReferenceSection />);

    await waitFor(() => expect(input()).toHaveValue(1800));
    expect(save()).toHaveAttribute("aria-disabled", "true");
  });
});
