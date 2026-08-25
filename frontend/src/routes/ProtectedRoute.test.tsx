import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import ProtectedRoute from "./ProtectedRoute";
import { useAuth } from "../auth/AuthContext";

// 진짜 AuthProvider는 마운트하자마자 토큰 재발급을 호출한다. 여기서 보려는 것은
// "로그인 안 된 상태에서 보호된 경로에 들어가면 무엇이 남는가"뿐이라 상태만 바꿔 끼운다.
vi.mock("../auth/AuthContext", () => ({ useAuth: vi.fn() }));

const useAuthMock = vi.mocked(useAuth);

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<LoginProbe />} />
        <Route
          path="/history"
          element={
            <ProtectedRoute>
              <p>히스토리 화면</p>
            </ProtectedRoute>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

// 진짜 LoginPage 대신 "무엇을 들고 왔는지"만 드러낸다. ProtectedRoute가 넘긴 이유가
// 라우터 state에 실려 도착하는지가 여기서 볼 전부다.
function LoginProbe() {
  const state = useLocation().state as { sessionExpired?: boolean } | null;
  return <p>{state?.sessionExpired ? "만료로 밀려난 로그인 화면" : "로그인 화면"}</p>;
}

function authState(state: {
  isAuthenticated: boolean;
  isLoading: boolean;
  sessionExpired?: boolean;
}) {
  useAuthMock.mockReturnValue({
    sessionExpired: false,
    ...state,
    login: vi.fn(),
    logout: vi.fn(),
    withdraw: vi.fn(),
  });
}

beforeEach(() => {
  sessionStorage.clear();
  useAuthMock.mockReset();
});

describe("ProtectedRoute", () => {
  it("로그인이 안 됐으면 가려던 경로를 남기고 로그인 화면으로 보낸다", () => {
    authState({ isAuthenticated: false, isLoading: false });

    renderAt("/history?days=30");

    expect(screen.getByText("로그인 화면")).toBeInTheDocument();
    // 이 값이 없으면 로그인 후 무조건 첫 화면으로 떨어져, 사용자가 히스토리를 다시 찾아가야 한다.
    expect(sessionStorage.getItem("returnTo")).toBe("/history?days=30");
  });

  it("세션 복원을 기다리는 동안에는 로그인 화면으로 보내지 않는다", () => {
    // 새로고침 직후에는 잠깐 미인증 상태다. 여기서 넘겨보내면 이미 로그인한 사용자가
    // 새로고침할 때마다 로그인 화면을 스쳐 지나간다.
    authState({ isAuthenticated: false, isLoading: true });

    renderAt("/history");

    expect(screen.queryByText("로그인 화면")).not.toBeInTheDocument();
    expect(sessionStorage.getItem("returnTo")).toBeNull();
  });

  // 이 신호가 없으면 로그인 화면은 "쓰다가 끊긴 사람"과 "그냥 처음 들어온 사람"을
  // 구분할 수 없어, 세션이 끊긴 사용자는 아무 설명 없이 화면만 바뀐 것을 보게 된다.
  it("세션이 끊겨 밀려난 경우에는 그 이유를 로그인 화면까지 들고 간다", () => {
    authState({ isAuthenticated: false, isLoading: false, sessionExpired: true });

    renderAt("/history");

    expect(screen.getByText("만료로 밀려난 로그인 화면")).toBeInTheDocument();
  });

  // 반대쪽도 지켜야 한다 — 처음 온 방문자에게 "세션이 끊겼다"고 말하면 있지도 않았던
  // 로그인을 잃어버린 것처럼 들린다.
  it("로그인한 적 없이 들어온 경우에는 만료 이유를 붙이지 않는다", () => {
    authState({ isAuthenticated: false, isLoading: false, sessionExpired: false });

    renderAt("/history");

    expect(screen.getByText("로그인 화면")).toBeInTheDocument();
  });

  it("로그인 상태면 아무것도 남기지 않고 그대로 보여준다", () => {
    authState({ isAuthenticated: true, isLoading: false });

    renderAt("/history");

    expect(screen.getByText("히스토리 화면")).toBeInTheDocument();
    expect(sessionStorage.getItem("returnTo")).toBeNull();
  });
});
