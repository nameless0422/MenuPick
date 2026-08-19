import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import LoginPage from "./LoginPage";
import { useAuth } from "../auth/AuthContext";

vi.mock("../api/pick", () => ({ requestDemoPick: vi.fn() }));
vi.mock("../api/auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/auth")>()),
  login: vi.fn(),
  resendVerification: vi.fn(),
}));
vi.mock("../auth/AuthContext", () => ({ useAuth: vi.fn() }));

const originalLocation = window.location;

function renderLogin(state?: unknown) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[{ pathname: "/login", state }]}>
        <LoginPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  sessionStorage.clear();
  vi.mocked(useAuth).mockReturnValue({
    isAuthenticated: false,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
    withdraw: vi.fn(),
  });
  Object.defineProperty(window, "location", {
    configurable: true,
    writable: true,
    value: { href: "http://localhost/login" },
  });
});

afterEach(() => {
  Object.defineProperty(window, "location", {
    configurable: true,
    writable: true,
    value: originalLocation,
  });
});

describe("LoginPage 소셜 로그인 안내", () => {
  // 소셜은 가입 경로가 아니다. 이 안내가 없으면 거절당한 사용자는 계정이 있는데도
  // 왜 안 되는지 모른 채 같은 버튼만 다시 누른다.
  it("연동 안 된 소셜로 로그인을 시도해 되돌아오면 무엇을 해야 하는지 알려준다", () => {
    renderLogin({ socialNotLinked: "kakao" });

    expect(screen.getByRole("status")).toHaveTextContent(
      /카카오 계정이 아직 연동돼 있지 않아요/,
    );
    // 안내만 하고 끝내면 갈 곳이 없다 — 가입으로 가는 길이 같은 문장 안에 있어야 한다.
    expect(screen.getByRole("link", { name: "이메일로 가입" })).toHaveAttribute("href", "/signup");
  });

  it("그냥 들어온 로그인 화면에는 그 안내를 띄우지 않는다", () => {
    renderLogin();

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("소셜 버튼은 login 모드로 인가 요청을 시작한다", async () => {
    const user = userEvent.setup();
    renderLogin();

    await user.click(screen.getByRole("button", { name: "카카오로 로그인" }));

    expect(window.location.href).toContain("/api/v1/auth/kakao/authorize");
    expect(sessionStorage.getItem("oauth_request_kakao")).toContain('"mode":"login"');
  });
});
