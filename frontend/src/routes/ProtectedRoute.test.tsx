import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
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
        <Route path="/login" element={<p>로그인 화면</p>} />
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

function authState(state: { isAuthenticated: boolean; isLoading: boolean }) {
  useAuthMock.mockReturnValue({
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

  it("로그인 상태면 아무것도 남기지 않고 그대로 보여준다", () => {
    authState({ isAuthenticated: true, isLoading: false });

    renderAt("/history");

    expect(screen.getByText("히스토리 화면")).toBeInTheDocument();
    expect(sessionStorage.getItem("returnTo")).toBeNull();
  });
});
