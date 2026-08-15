import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { rememberReturnTo } from "../auth/returnTo";

export default function ProtectedRoute({ children }: { children: ReactNode }) {
  const { isAuthenticated, isLoading } = useAuth();
  const location = useLocation();

  if (isLoading) return <p>로딩 중...</p>;

  if (!isAuthenticated) {
    // 어디로 가려다 막혔는지 남긴다. 로그인 후 무조건 첫 화면으로 보내면, 히스토리에서
    // 세션이 끊긴 사용자는 로그인하고 나서 히스토리를 다시 찾아 들어가야 한다.
    // 렌더 중 호출이라 StrictMode에서 두 번 실행되지만 같은 값을 덮어쓸 뿐이다.
    rememberReturnTo(location.pathname + location.search);
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
}
