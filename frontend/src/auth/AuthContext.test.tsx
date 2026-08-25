import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AuthProvider, useAuth } from "./AuthContext";
import { refreshAccessToken, setSessionExpiredHandler } from "../api/http";
import { logout as apiLogout } from "../api/auth";

// 진짜 http를 쓰면 마운트하자마자 네트워크로 나간다. 여기서 볼 것은 통신이 아니라
// "인증이 풀린 이유를 구분해서 들고 있는가"라서, 인터셉터가 부르는 통로만 손에 쥔다.
vi.mock("../api/http", () => ({
  refreshAccessToken: vi.fn(),
  setAccessToken: vi.fn(),
  setSessionExpiredHandler: vi.fn(),
}));
vi.mock("../api/auth", () => ({ logout: vi.fn(), withdraw: vi.fn() }));

/** AuthProvider가 인터셉터에 등록해 둔 만료 통지 함수. 401 재발급 실패가 이걸 부른다. */
function registeredExpiredHandler() {
  const calls = vi.mocked(setSessionExpiredHandler).mock.calls;
  const handler = calls.map(([fn]) => fn).filter(Boolean).at(-1);
  if (!handler) throw new Error("AuthProvider가 만료 핸들러를 등록하지 않았다");
  return handler;
}

function Probe() {
  const { isAuthenticated, isLoading, sessionExpired, login, logout } = useAuth();
  return (
    <div>
      <p>인증 {String(isAuthenticated)}</p>
      <p>로딩 {String(isLoading)}</p>
      <p>만료 {String(sessionExpired)}</p>
      <button onClick={() => login("새-토큰")}>로그인</button>
      <button onClick={() => logout()}>로그아웃</button>
    </div>
  );
}

async function renderAuth() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Probe />
      </AuthProvider>
    </QueryClientProvider>,
  );
  // 부팅 시 재발급 시도가 끝나기 전에는 아직 아무 상태도 확정되지 않았다.
  await waitFor(() => expect(screen.getByText("로딩 false")).toBeInTheDocument());
}

beforeEach(() => {
  vi.mocked(refreshAccessToken).mockResolvedValue("복원된-토큰");
  vi.mocked(apiLogout).mockResolvedValue(undefined as never);
  vi.mocked(setSessionExpiredHandler).mockClear();
});

describe("세션이 풀린 이유를 구분한다", () => {
  // clearSession은 두 경로에서 똑같이 불린다. 신호가 없으면 로그인 화면은 두 경우를
  // 구분할 수 없어, 쓰다가 끊긴 사용자에게 아무 설명도 못 한다.
  it("인터셉터가 알린 만료는 만료로 표시된다", async () => {
    await renderAuth();
    const notifyExpired = registeredExpiredHandler();

    act(() => notifyExpired());

    expect(screen.getByText("만료 true")).toBeInTheDocument();
    expect(screen.getByText("인증 false")).toBeInTheDocument();
  });

  // 자기가 누른 로그아웃을 "세션이 끊겼다"고 알리면 사고가 난 것처럼 들린다.
  it("사용자가 직접 누른 로그아웃은 만료가 아니다", async () => {
    const user = userEvent.setup();
    await renderAuth();

    await user.click(screen.getByRole("button", { name: "로그아웃" }));

    await waitFor(() => expect(screen.getByText("인증 false")).toBeInTheDocument());
    expect(screen.getByText("만료 false")).toBeInTheDocument();
  });

  // 안 내리면 다음번에 스스로 로그아웃했을 때 지난 만료 안내가 되살아난다.
  it("다시 로그인하면 만료 표시가 내려간다", async () => {
    const user = userEvent.setup();
    await renderAuth();
    act(() => registeredExpiredHandler()());
    expect(screen.getByText("만료 true")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(screen.getByText("만료 false")).toBeInTheDocument();
    expect(screen.getByText("인증 true")).toBeInTheDocument();
  });
});
