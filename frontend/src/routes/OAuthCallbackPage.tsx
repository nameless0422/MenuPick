import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { loginWithOAuth, type Provider } from "../api/auth";
import { useAuth } from "../auth/AuthContext";
import { consumeState } from "../auth/oauthUrls";
import { consumeReturnTo } from "../auth/returnTo";

const INVALID_REQUEST_MESSAGE =
  "로그인 요청이 유효하지 않습니다. 다시 시도해주세요.";

/** 이메일 로그인과 같은 기본 착지점 — 로그인 수단에 따라 도착지가 달라지지 않게 맞춘다. */
const DEFAULT_LANDING = "/menus";

export default function OAuthCallbackPage({ provider }: { provider: Provider }) {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { login } = useAuth();
  const [error, setError] = useState<string | null>(null);
  // StrictMode에서 effect가 두 번 실행되는 것을 막는다 — 인가 코드는 1회용이라
  // 두 번째 호출은 항상 실패한다. state 검증도 1회성(consume)이므로 같은 가드를 공유한다.
  const requested = useRef(false);

  useEffect(() => {
    if (requested.current) return;
    requested.current = true;

    // state를 먼저 대조한다 — 우리가 시작하지 않은 로그인(로그인 CSRF)이면
    // 인가 코드를 서버로 보내지 않고 여기서 끊는다.
    if (!consumeState(provider, searchParams.get("state"))) {
      setError(INVALID_REQUEST_MESSAGE);
      return;
    }

    const code = searchParams.get("code");
    if (!code) {
      setError("인가 코드가 없습니다.");
      return;
    }

    loginWithOAuth(provider, code)
      .then((accessToken) => {
        login(accessToken);
        navigate(consumeReturnTo(DEFAULT_LANDING), { replace: true });
      })
      .catch(() => setError("로그인에 실패했습니다."));
  }, [searchParams, provider, login, navigate]);

  if (error) return <p>{error} <a href="/login">다시 로그인</a></p>;
  return <p>로그인 처리 중...</p>;
}
