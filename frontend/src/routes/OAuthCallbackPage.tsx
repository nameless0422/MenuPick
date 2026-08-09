import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { loginWithOAuth, type Provider } from "../api/auth";
import { useAuth } from "../auth/AuthContext";

export default function OAuthCallbackPage({ provider }: { provider: Provider }) {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { login } = useAuth();
  const [error, setError] = useState<string | null>(null);
  // StrictMode에서 effect가 두 번 실행되는 것을 막는다 — 인가 코드는 1회용이라
  // 두 번째 호출은 항상 실패한다.
  const requested = useRef(false);

  useEffect(() => {
    const code = searchParams.get("code");
    if (!code) {
      setError("인가 코드가 없습니다.");
      return;
    }
    if (requested.current) return;
    requested.current = true;

    loginWithOAuth(provider, code)
      .then((accessToken) => {
        login(accessToken);
        navigate("/menus", { replace: true });
      })
      .catch(() => setError("로그인에 실패했습니다."));
  }, [searchParams, provider, login, navigate]);

  if (error) return <p>{error} <a href="/login">다시 로그인</a></p>;
  return <p>로그인 처리 중...</p>;
}
