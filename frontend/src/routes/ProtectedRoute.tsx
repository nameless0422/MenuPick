import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { rememberReturnTo } from "../auth/returnTo";

export default function ProtectedRoute({ children }: { children: ReactNode }) {
  const { isAuthenticated, isLoading, sessionExpired } = useAuth();
  const location = useLocation();

  if (isLoading) return <p>로딩 중...</p>;

  if (!isAuthenticated) {
    // 어디로 가려다 막혔는지 남긴다. 로그인 후 무조건 첫 화면으로 보내면, 히스토리에서
    // 세션이 끊긴 사용자는 로그인하고 나서 히스토리를 다시 찾아 들어가야 한다.
    // 렌더 중 호출이라 StrictMode에서 두 번 실행되지만 같은 값을 덮어쓸 뿐이다.
    rememberReturnTo(location.pathname + location.search);
    // 왜 로그인 화면에 와 있는지를 함께 넘긴다. 여기서 넘기지 않으면 로그인 화면은
    // 쓰다가 세션이 끊긴 사람과 그냥 처음 들어온 사람을 구분할 수 없어, 화면만 바뀌고
    // 아무 설명이 없다. 라우터 state로 보내는 이유는 이 안내가 "이번 이동에만" 붙어야
    // 하기 때문이다 — 저장해두면 한참 뒤 직접 /login에 들어온 사람에게도 다시 뜬다.
    // (연동 안 된 소셜 로그인 안내가 이미 쓰는 방식과 같다.)
    return (
      <Navigate to="/login" replace state={sessionExpired ? { sessionExpired: true } : undefined} />
    );
  }

  return <>{children}</>;
}
