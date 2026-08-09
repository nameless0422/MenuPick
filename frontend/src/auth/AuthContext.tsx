import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { refreshAccessToken, setAccessToken } from "../api/http";
import { logout as apiLogout } from "../api/auth";

interface AuthContextValue {
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (token: string) => void;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [isLoading, setIsLoading] = useState(true);

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

  async function logout() {
    try {
      await apiLogout();
    } finally {
      setAccessToken(null);
      setIsAuthenticated(false);
    }
  }

  return (
    <AuthContext.Provider value={{ isAuthenticated, isLoading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth는 AuthProvider 안에서만 쓸 수 있다");
  return ctx;
}
