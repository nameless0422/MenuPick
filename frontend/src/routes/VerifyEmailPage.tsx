import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { verifyEmail } from "../api/auth";
import { useAuth } from "../auth/AuthContext";
import { apiErrorMessage } from "../api/http";
import "./AuthPages.css";

/**
 * 인증 메일 링크의 착지점. 토큰을 서버에 넘기고 성공하면 곧바로 로그인 상태로 들어간다.
 */
export default function VerifyEmailPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { login } = useAuth();
  const [requestError, setRequestError] = useState<string | null>(null);

  // 링크에 토큰이 있는지는 URL만 보면 알 수 있다 — 소비되지도, 서버에 묻지도 않는 검사라
  // effect까지 갈 이유가 없다. 렌더에서 바로 판별하면 "요청이 실패해서 생긴 오류"와
  // "애초에 보낼 수 없는 링크"가 섞이지 않는다.
  const token = searchParams.get("token");
  const error = token ? requestError : "인증 링크가 올바르지 않습니다.";

  // 토큰은 1회용이라 StrictMode의 두 번째 effect 실행은 반드시 실패한다.
  // OAuthCallbackPage와 같은 이유로 같은 가드를 둔다.
  const requested = useRef(false);

  useEffect(() => {
    if (!token) return;
    if (requested.current) return;
    requested.current = true;

    verifyEmail(token)
      .then((accessToken) => {
        login(accessToken);
        navigate("/menus", { replace: true });
      })
      .catch((e) => setRequestError(apiErrorMessage(e)));
  }, [token, login, navigate]);

  if (error) {
    return (
      <div className="login-page">
        <h1>인증하지 못했습니다</h1>
        <p className="error" role="alert">{error}</p>
        <div className="auth-links">
          <Link to="/login">로그인 화면에서 인증 메일 다시 받기</Link>
        </div>
      </div>
    );
  }

  return (
    <div className="login-page">
      <p>이메일 인증 처리 중…</p>
    </div>
  );
}
