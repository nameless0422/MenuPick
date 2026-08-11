import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { refreshAccessToken, setAccessToken } from "../api/http";
import { logout as apiLogout, withdraw as apiWithdraw } from "../api/auth";

interface AuthContextValue {
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (token: string) => void;
  logout: () => Promise<void>;
  withdraw: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const queryClient = useQueryClient();

  // Access Token은 메모리에만 있어 새로고침하면 사라진다 — 앱 부팅 시
  // Refresh Token 쿠키로 한 번 조용히 재발급을 시도해 로그인 상태를 복원한다.
  useEffect(() => {
    refreshAccessToken()
      .then(() => setIsAuthenticated(true))
      .catch(() => setIsAuthenticated(false))
      .finally(() => setIsLoading(false));
  }, []);

  function login(token: string) {
    setAccessToken(token);
    setIsAuthenticated(true);
  }

  // 세션 종료 시 공통 정리. react-query 캐시까지 비우는 이유는 공용 기기에서
  // 로그아웃 후 다른 계정으로 로그인하면 이전 사용자의 메뉴·히스토리가 잠깐
  // 보이기 때문이다. 로그아웃 진입점이 여러 곳(내비게이션·설정)이라 화면이 아니라
  // 여기에 둬야 경로마다 동작이 갈리지 않는다.
  function clearSession() {
    setAccessToken(null);
    setIsAuthenticated(false);
    queryClient.clear();
  }

  async function logout() {
    try {
      await apiLogout();
    } finally {
      clearSession();
    }
  }

  // 탈퇴 성공 시에만 정리한다 — 요청이 실패하면 계정은 그대로 살아 있으므로
  // 로그인 상태를 유지하고 에러를 화면으로 올려보낸다.
  async function withdraw() {
    await apiWithdraw();
    clearSession();
  }

  return (
    <AuthContext.Provider value={{ isAuthenticated, isLoading, login, logout, withdraw }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth는 AuthProvider 안에서만 쓸 수 있다");
  return ctx;
}
