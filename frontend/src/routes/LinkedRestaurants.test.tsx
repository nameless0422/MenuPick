import type { FormEvent } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import LinkedRestaurants from "./LinkedRestaurants";
import {
  deleteMenuRestaurant,
  fetchMenuRestaurants,
  updateMenuRestaurant,
} from "../api/menuRestaurants";

vi.mock("../api/menuRestaurants", () => ({
  fetchMenuRestaurants: vi.fn(),
  updateMenuRestaurant: vi.fn(),
  deleteMenuRestaurant: vi.fn(),
}));

const fetchMock = vi.mocked(fetchMenuRestaurants);
const updateMock = vi.mocked(updateMenuRestaurant);
const deleteMock = vi.mocked(deleteMenuRestaurant);

const JINJU = {
  menuId: 1,
  restaurantId: 10,
  restaurantName: "진주회관",
  restaurantAddress: "서울 중구 세종대로11길 26",
  rating: 3,
  memo: "점심에 웨이팅",
  createdAt: "2026-01-01T00:00:00",
  updatedAt: "2026-01-01T00:00:00",
  version: 7,
};

beforeEach(() => {
  fetchMock.mockReset();
  updateMock.mockReset();
  deleteMock.mockReset();
  fetchMock.mockResolvedValue({ menuRestaurants: [JINJU] });
  updateMock.mockResolvedValue({ ...JINJU, rating: 5, memo: "바뀐 메모", version: 8 });
  deleteMock.mockResolvedValue(undefined);
});

/**
 * 이 화면이 없어서 생겼던 결함을 고정한다.
 *
 * <p>백엔드에는 수정·해제 API가 처음부터 있었고 테스트도 있었지만, 프론트에는 연결을
 * <b>보여주는 자리 자체가 없었다</b>. 식당 화면에서 붙이는 것만 가능했으니 사용자는 한번
 * 붙인 식당을 뗄 수도, 별점을 고칠 수도 없었다 — 되돌릴 수 없는 일방통행이었다.
 * 기획 문서는 이 기능을 "구현 완료"로 적고 있었다(API가 끝난 것을 기능이 끝난 것으로 봤다).
 */
describe("메뉴에 연결된 식당", () => {
  it("연결된 식당과 별점·메모를 보여준다", async () => {
    renderWithProviders(<LinkedRestaurants menuId={1} />);

    expect(await screen.findByText("진주회관")).toBeInTheDocument();
    expect(screen.getByText("서울 중구 세종대로11길 26")).toBeInTheDocument();
    expect(screen.getByText("점심에 웨이팅")).toBeInTheDocument();
    // 별점을 글리프로만 두면 스크린리더에는 "검은 별"이 반복될 뿐 몇 점인지 알 수 없다.
    expect(screen.getByText("별점 5점 만점에 3점")).toBeInTheDocument();
  });

  it("연결이 없으면 무엇을 해야 하는지 알려준다", async () => {
    // 빈 목록에 아무 말도 없으면 "고장인지 원래 없는 건지"를 구분할 수 없다.
    fetchMock.mockResolvedValue({ menuRestaurants: [] });
    renderWithProviders(<LinkedRestaurants menuId={1} />);

    expect(await screen.findByText(/아직 연결된 식당이 없어요/)).toBeInTheDocument();
  });

  it("연결을 해제한다 — 확인을 거친 뒤에만", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    renderWithProviders(<LinkedRestaurants menuId={1} />);

    await user.click(await screen.findByRole("button", { name: "진주회관 연결 해제" }));

    // 되돌릴 수 없는 동작이라 무엇을 지우는지 이름으로 확인시킨다.
    expect(confirmSpy).toHaveBeenCalledWith("'진주회관' 연결을 해제할까요?");
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(1, 10));
    confirmSpy.mockRestore();
  });

  it("확인을 취소하면 해제하지 않는다", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    renderWithProviders(<LinkedRestaurants menuId={1} />);

    await user.click(await screen.findByRole("button", { name: "진주회관 연결 해제" }));

    expect(deleteMock).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it("별점과 메모를 고쳐 저장한다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LinkedRestaurants menuId={1} />);

    await user.click(await screen.findByRole("button", { name: "진주회관 연결 수정" }));
    await user.click(screen.getByRole("button", { name: "별점 5" }));

    const memo = screen.getByRole("textbox", { name: "메모" });
    await user.clear(memo);
    await user.type(memo, "바뀐 메모");
    await user.click(screen.getByRole("button", { name: "연결 저장" }));

    await waitFor(() => expect(updateMock).toHaveBeenCalled());
    // 화면을 그릴 때 받은 버전을 그대로 돌려보내야 서버가 "그 사이 누가 고쳤는가"를 판정할 수 있다.
    expect(updateMock).toHaveBeenCalledWith(1, 10, {
      rating: 5,
      memo: "바뀐 메모",
      version: 7,
    });
  });

  it("별점을 지우면 null로 보낸다 — 0은 서버의 1~5 범위 밖이다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LinkedRestaurants menuId={1} />);

    await user.click(await screen.findByRole("button", { name: "진주회관 연결 수정" }));
    await user.click(screen.getByRole("button", { name: "별점 지우기" }));
    await user.click(screen.getByRole("button", { name: "연결 저장" }));

    await waitFor(() => expect(updateMock).toHaveBeenCalled());
    expect(updateMock.mock.calls[0][2].rating).toBeNull();
  });

  /**
   * 이 컴포넌트는 메뉴 수정 {@code <form>} 안에 들어간다. form 중첩은 HTML상 허용되지 않아
   * 안쪽 form을 쓸 수 없고, 그래서 메모 입력의 Enter를 직접 가로채지 않으면 바깥 폼이
   * 제출된다 — 메뉴 전체가 저장되고 편집 중이던 연결 값은 사라진다.
   */
  it("메모에서 Enter를 쳐도 바깥 폼이 제출되지 않고 이 연결만 저장된다", async () => {
    const user = userEvent.setup();
    const onOuterSubmit = vi.fn((e: FormEvent) => e.preventDefault());
    renderWithProviders(
      <form onSubmit={onOuterSubmit}>
        <LinkedRestaurants menuId={1} />
      </form>,
    );

    await user.click(await screen.findByRole("button", { name: "진주회관 연결 수정" }));
    await user.type(screen.getByRole("textbox", { name: "메모" }), "{Enter}");

    await waitFor(() => expect(updateMock).toHaveBeenCalled());
    expect(onOuterSubmit).not.toHaveBeenCalled();
  });

  it("한 번에 한 줄만 편집한다", async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValue({
      menuRestaurants: [JINJU, { ...JINJU, restaurantId: 11, restaurantName: "덕천식당" }],
    });
    renderWithProviders(<LinkedRestaurants menuId={1} />);

    await user.click(await screen.findByRole("button", { name: "진주회관 연결 수정" }));
    await user.click(screen.getByRole("button", { name: "덕천식당 연결 수정" }));

    // 저장하지 않은 값이 여러 줄에 흩어져 있으면 사용자가 어디에 무엇이 남았는지 추적할 수 없다.
    const list = screen.getByRole("list");
    expect(within(list).getAllByRole("button", { name: "연결 저장" })).toHaveLength(1);
  });
});
