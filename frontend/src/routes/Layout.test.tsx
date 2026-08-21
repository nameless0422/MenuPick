import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import Layout from "./Layout";
import { useAuth } from "../auth/AuthContext";

vi.mock("../auth/AuthContext", () => ({ useAuth: vi.fn() }));

beforeEach(() => {
  document.title = "";
  vi.mocked(useAuth).mockReturnValue({
    isAuthenticated: true,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
    withdraw: vi.fn(),
  });
});

function renderShell(initial = "/pick") {
  return render(
    <MemoryRouter initialEntries={[initial]}>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/pick" element={<h1>오늘 뭐 먹지</h1>} />
          <Route path="/history" element={<h1>픽 히스토리</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * SPA는 페이지를 갈아 끼워도 브라우저가 문서 제목이나 초점을 건드리지 않는다.
 * 손으로 넣지 않으면 다섯 화면이 전부 같은 제목을 쓰고, 초점은 방금 누른 nav
 * 링크에 남아 다음 Tab이 본문을 통째로 지나친다. 화면만 보면 멀쩡해 보인다.
 */
describe("Layout 라우트 전환", () => {
  it("라우트마다 문서 제목이 다르다", async () => {
    const user = userEvent.setup();
    renderShell();

    await waitFor(() => expect(document.title).toBe("오늘 뭐 먹지 · 메뉴픽"));

    await user.click(screen.getByRole("link", { name: "히스토리" }));

    await waitFor(() => expect(document.title).toBe("픽 히스토리 · 메뉴픽"));
  });

  it("라우트를 옮기면 초점이 본문으로 간다", async () => {
    const user = userEvent.setup();
    renderShell();

    await user.click(screen.getByRole("link", { name: "히스토리" }));

    await waitFor(() => expect(screen.getByRole("main")).toHaveFocus());
  });

  it("첫 렌더에서는 초점을 빼앗지 않는다", async () => {
    // 주소창으로 바로 들어왔거나 새로고침한 경우다. 아직 아무 데도 손대지 않은
    // 초점을 옮기면 오히려 사용자를 놀라게 한다.
    renderShell();

    await waitFor(() => expect(document.title).toBe("오늘 뭐 먹지 · 메뉴픽"));
    expect(screen.getByRole("main")).not.toHaveFocus();
  });
});

describe("Layout 내비게이션", () => {
  it("현재 페이지를 aria-current로 알린다", async () => {
    const user = userEvent.setup();
    renderShell();

    expect(screen.getByRole("link", { name: "오늘 뭐 먹지" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: "히스토리" })).not.toHaveAttribute("aria-current");

    await user.click(screen.getByRole("link", { name: "히스토리" }));

    expect(screen.getByRole("link", { name: "히스토리" })).toHaveAttribute("aria-current", "page");
  });

  it("본문으로 건너뛰는 링크가 있다", () => {
    renderShell();

    const skip = screen.getByRole("link", { name: "본문으로 건너뛰기" });
    expect(skip).toHaveAttribute("href", "#main");
    // 링크가 가리키는 곳이 실제로 있어야 의미가 있다
    expect(screen.getByRole("main")).toHaveAttribute("id", "main");
  });

  it("내비게이션에 이름이 있어 랜드마크 목록에서 구분된다", () => {
    renderShell();

    expect(screen.getByRole("navigation", { name: "주요 메뉴" })).toBeInTheDocument();
  });
});
