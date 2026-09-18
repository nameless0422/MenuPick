import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import PickRoomPage from "./PickRoomPage";
import {
  decidePickRoom,
  fetchPickRoom,
  replacePickRoomVetoes,
  type PickRoom,
} from "../api/pickRooms";

vi.mock("../api/pickRooms", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/pickRooms")>()),
  fetchPickRoom: vi.fn(),
  replacePickRoomVetoes: vi.fn(),
  decidePickRoom: vi.fn(),
}));

const fetchMock = vi.mocked(fetchPickRoom);
const vetoMock = vi.mocked(replacePickRoomVetoes);
const decideMock = vi.mocked(decidePickRoom);

const room = (overrides: Partial<PickRoom> = {}): PickRoom => ({
  code: "abc123",
  expiresAt: "2026-09-19T18:00:00",
  menus: [
    { id: 1, name: "김치찌개", vetoedBy: 0, vetoedByMe: false },
    { id: 2, name: "파스타", vetoedBy: 2, vetoedByMe: false },
  ],
  participantCount: 2,
  decision: null,
  ...overrides,
});

/** 방 화면은 /rooms/:code에서만 뜻이 있다 — 경로 파라미터까지 포함해 띄운다. */
function renderRoom() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/rooms/abc123"]}>
        <Routes>
          <Route path="/rooms/:code" element={<PickRoomPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
  fetchMock.mockReset();
  vetoMock.mockReset();
  decideMock.mockReset();
  fetchMock.mockResolvedValue(room());
  vetoMock.mockImplementation(async (_code, _participant, ids) =>
    room({
      menus: [
        { id: 1, name: "김치찌개", vetoedBy: ids.includes(1) ? 1 : 0, vetoedByMe: ids.includes(1) },
        { id: 2, name: "파스타", vetoedBy: 2, vetoedByMe: ids.includes(2) },
      ],
    }),
  );
  decideMock.mockResolvedValue(
    room({ decision: { menuName: "김치찌개", decidedAt: "2026-09-19T12:30:00" } }),
  );
});

describe("여럿이 같이 뽑기 — 방", () => {
  it("메뉴와 함께 몇 명이 참여했고 몇 개가 남았는지 보여준다", async () => {
    renderRoom();

    expect(await screen.findByRole("button", { name: /김치찌개/ })).toBeInTheDocument();
    const line = screen.getByText(/참여했고/);
    expect(line).toHaveTextContent("2명");
    // 파스타는 이미 2명이 뺐으므로 남은 것은 김치찌개 하나다.
    expect(line).toHaveTextContent("1개");
  });

  /** 로그인 화면으로 튕기면 링크를 받은 사람은 아무것도 할 수 없다. */
  it("로그인 없이도 열린다 — 인증 관련 요청을 하지 않는다", async () => {
    renderRoom();

    await screen.findByRole("button", { name: /김치찌개/ });
    expect(screen.queryByText(/로그인/)).toBeNull();
  });

  it("메뉴를 누르면 지금 빼 둔 목록 전체를 보낸다", async () => {
    const user = userEvent.setup();
    renderRoom();

    await user.click(await screen.findByRole("button", { name: /김치찌개/ }));

    await waitFor(() => expect(vetoMock).toHaveBeenCalledWith("abc123", expect.any(String), [1]));
    // 누른 즉시 눌린 상태로 보여야 한다(낙관적 표시).
    expect(screen.getByRole("button", { name: /김치찌개/ })).toHaveAttribute("aria-pressed", "true");
  });

  /** 제출은 전체 교체다. 해제가 "변경 없음"으로 전달되면 화면과 서버가 갈라진다. */
  it("다시 누르면 빈 목록을 보내 제외가 풀린다", async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValue(
      room({ menus: [{ id: 1, name: "김치찌개", vetoedBy: 1, vetoedByMe: true }] }),
    );
    renderRoom();

    await user.click(await screen.findByRole("button", { name: /김치찌개/ }));

    await waitFor(() => expect(vetoMock).toHaveBeenCalledWith("abc123", expect.any(String), []));
  });

  it("제출에 실패하면 이유를 보여주고 낙관적 표시를 거둔다", async () => {
    const user = userEvent.setup();
    vetoMock.mockRejectedValue(new Error("일시적인 오류"));
    renderRoom();

    await user.click(await screen.findByRole("button", { name: /김치찌개/ }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /김치찌개/ })).toHaveAttribute("aria-pressed", "false"),
    );
  });

  it("뽑으면 결과를 크게 보여주고 다시 못 뽑는다고 알린다", async () => {
    const user = userEvent.setup();
    renderRoom();

    await user.click(await screen.findByRole("button", { name: /남은 메뉴 중에서 뽑기/ }));

    const result = await screen.findByRole("status");
    expect(result).toHaveTextContent("김치찌개");
    expect(screen.getByText(/한 번 정해진 결과는 바뀌지 않아요/)).toBeInTheDocument();
    // 결과가 나오면 제외도 뽑기도 더는 없다.
    expect(screen.queryByRole("button", { name: /남은 메뉴 중에서 뽑기/ })).toBeNull();
  });

  /** 다른 사람이 먼저 눌러 결과가 들어오는 경우다 — 폴링으로 도착해도 같은 화면이어야 한다. */
  it("이미 정해진 방은 들어가자마자 결과만 보여준다", async () => {
    fetchMock.mockResolvedValue(
      room({ decision: { menuName: "파스타", decidedAt: "2026-09-19T12:30:00" } }),
    );
    renderRoom();

    expect(await screen.findByRole("status")).toHaveTextContent("파스타");
    expect(screen.queryByRole("button", { name: /김치찌개/ })).toBeNull();
  });

  it("없거나 만료된 방이면 무엇을 해야 하는지 알려준다", async () => {
    fetchMock.mockRejectedValue(new Error("방을 찾을 수 없거나 이미 만료되었습니다."));
    renderRoom();

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByText(/새 링크를 받아주세요/)).toBeInTheDocument();
  });
});
