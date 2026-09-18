import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import StartPickRoom from "./StartPickRoom";
import { createPickRoom, type PickRoom } from "../api/pickRooms";

vi.mock("../api/pickRooms", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/pickRooms")>()),
  createPickRoom: vi.fn(),
}));

const createMock = vi.mocked(createPickRoom);

const room: PickRoom = {
  code: "abc123",
  expiresAt: "2026-09-19T18:00:00",
  menus: [
    { id: 1, name: "김치찌개", vetoedBy: 0, vetoedByMe: false },
    { id: 2, name: "파스타", vetoedBy: 0, vetoedByMe: false },
  ],
  participantCount: 0,
  decision: null,
};

beforeEach(() => {
  createMock.mockReset();
  createMock.mockResolvedValue(room);
});

afterEach(() => {
  Reflect.deleteProperty(navigator, "share");
});

const start = async (categories: string[] = []) => {
  const user = userEvent.setup();
  renderWithProviders(<StartPickRoom categories={categories} />);
  await user.click(screen.getByRole("button", { name: "여럿이 같이 뽑기" }));
  return user;
};

describe("같이 뽑기 시작하기", () => {
  it("방을 만들고 링크를 바로 보여준다", async () => {
    await start();

    const link = await screen.findByRole("textbox", { name: "방 링크" });
    expect(link).toHaveValue(`${window.location.origin}/rooms/abc123`);
    expect(screen.getByRole("link", { name: /방 열기/ })).toHaveAttribute("href", "/rooms/abc123");
    // 링크를 받은 사람이 가입해야 한다면 방을 만들 이유가 없다 — 그 사실을 화면이 말한다.
    expect(screen.getByText(/가입 없이/)).toBeInTheDocument();
  });

  it("픽 화면에서 고른 카테고리를 그대로 방에 싣는다", async () => {
    await start(["한식", "중식"]);

    await waitFor(() => expect(createMock).toHaveBeenCalledWith(["한식", "중식"]));
  });

  it("카테고리를 안 골랐으면 빈 목록으로 만든다", async () => {
    await start();

    await waitFor(() => expect(createMock).toHaveBeenCalledWith([]));
  });

  it("공유 버튼은 방 링크를 공유한다", async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "share", { value: share, configurable: true });
    const user = await start();

    await user.click(await screen.findByRole("button", { name: /링크 공유하기/ }));

    await waitFor(() =>
      expect(share).toHaveBeenCalledWith(
        expect.objectContaining({ url: `${window.location.origin}/rooms/abc123` }),
      ),
    );
  });

  it("만들기에 실패하면 이유를 보여준다", async () => {
    createMock.mockRejectedValue(new Error("열려 있는 방이 너무 많습니다."));
    await start();

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "방 링크" })).toBeNull();
  });
});
