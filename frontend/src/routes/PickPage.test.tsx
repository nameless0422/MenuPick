import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import PickPage from "./PickPage";
import { requestPick } from "../api/pick";

vi.mock("../api/pick", () => ({ requestPick: vi.fn() }));
vi.mock("../api/tags", () => ({ searchTags: vi.fn().mockResolvedValue([]) }));

const requestPickMock = vi.mocked(requestPick);

/**
 * 위치 요청을 붙잡아 두는 스텁.
 *
 * <p>실제 {@code getCurrentPosition}은 취소할 수단이 없고 최대 10초까지 매달린다. 사용자가
 * 그 사이에 필터를 끄거나 다시 켜는 것이 이 파일이 검증하는 상황이라, 콜백을 언제 부를지
 * 테스트가 직접 정할 수 있어야 한다.
 */
let pendingRequests: {
  success: PositionCallback;
  error: PositionErrorCallback | null;
}[] = [];

function resolveRequest(index: number, latitude: number, longitude: number) {
  const request = pendingRequests[index];
  act(() => {
    request.success({ coords: { latitude, longitude } } as GeolocationPosition);
  });
}

function failRequest(index: number) {
  const request = pendingRequests[index];
  act(() => {
    request.error?.({ code: 1 } as GeolocationPositionError);
  });
}

beforeEach(() => {
  pendingRequests = [];
  requestPickMock.mockReset();
  // 이 파일이 보는 것은 픽 요청에 무엇이 실려 나가는지이므로 응답은 최소 형태로 고정한다.
  requestPickMock.mockResolvedValue({
    historyId: 1,
    menu: {
      id: 1,
      name: "김치찌개",
      memo: null,
      categories: [],
      tags: [],
      weight: 1,
      isExcluded: false,
      createdAt: "2026-01-01T00:00:00",
      updatedAt: "2026-01-01T00:00:00",
    },
    restaurants: [],
  });

  Object.defineProperty(navigator, "geolocation", {
    configurable: true,
    value: {
      getCurrentPosition: (success: PositionCallback, error?: PositionErrorCallback) => {
        pendingRequests.push({ success, error: error ?? null });
      },
    },
  });
});

const distanceToggle = () => screen.getByRole("checkbox", { name: /내 위치 기준으로/ });
const spinButton = () => screen.getByRole("button", { name: /오늘의 메뉴 뽑기/ });

describe("PickPage 거리 필터 — 위치 요청 취소", () => {
  it("취소한 뒤 위치가 도착해도 필터가 스스로 켜지지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(distanceToggle());
    await waitFor(() => expect(pendingRequests).toHaveLength(1));

    // 사용자가 기다리다 껐다
    await user.click(distanceToggle());
    expect(distanceToggle()).not.toBeChecked();

    // 그 뒤에야 위치가 도착한다
    resolveRequest(0, 37.5, 127.0);

    expect(distanceToggle()).not.toBeChecked();
  });

  it("취소했으면 픽 요청에 좌표가 실리지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(distanceToggle());
    await waitFor(() => expect(pendingRequests).toHaveLength(1));
    await user.click(distanceToggle());
    resolveRequest(0, 37.5, 127.0);

    await user.click(spinButton());

    await waitFor(() => expect(requestPickMock).toHaveBeenCalledTimes(1));
    const sent = requestPickMock.mock.calls[0][0];
    expect(sent.latitude).toBeUndefined();
    expect(sent.longitude).toBeUndefined();
    expect(sent.maxDistance).toBeUndefined();
  });

  it("껐다 켤 때 먼저 보낸 요청의 오래된 좌표가 최신 좌표를 덮어쓰지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(distanceToggle()); // 요청 A
    await waitFor(() => expect(pendingRequests).toHaveLength(1));
    await user.click(distanceToggle()); // 껐다
    await user.click(distanceToggle()); // 요청 B
    await waitFor(() => expect(pendingRequests).toHaveLength(2));

    // A가 B보다 늦게 도착하는 순서
    resolveRequest(1, 37.5665, 126.978);
    resolveRequest(0, 11.11, 22.22);

    await user.click(spinButton());

    await waitFor(() => expect(requestPickMock).toHaveBeenCalledTimes(1));
    const sent = requestPickMock.mock.calls[0][0];
    expect(sent.latitude).toBe(37.5665);
    expect(sent.longitude).toBe(126.978);
  });

  it("취소한 뒤 도착한 실패는 권한 오류 안내를 띄우지 않는다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(distanceToggle());
    await waitFor(() => expect(pendingRequests).toHaveLength(1));
    await user.click(distanceToggle());

    failRequest(0);

    expect(screen.queryByText(/위치를 가져오지 못해/)).not.toBeInTheDocument();
  });

  it("정상 흐름에서는 좌표와 거리 상한이 픽 요청에 실린다", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PickPage />);

    await user.click(distanceToggle());
    await waitFor(() => expect(pendingRequests).toHaveLength(1));
    resolveRequest(0, 37.5665, 126.978);

    await user.click(spinButton());

    await waitFor(() => expect(requestPickMock).toHaveBeenCalledTimes(1));
    const sent = requestPickMock.mock.calls[0][0];
    expect(sent.latitude).toBe(37.5665);
    expect(sent.longitude).toBe(126.978);
    expect(sent.maxDistance).toBe(500);
  });
});
