import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation } from "@tanstack/react-query";
import { kakaoAuthorizeUrl, googleAuthorizeUrl } from "../auth/oauthUrls";
import { requestDemoPick } from "../api/pick";
import { login as apiLogin, resendVerification } from "../api/auth";
import { useAuth } from "../auth/AuthContext";
import { apiErrorCode, apiErrorMessage } from "../api/http";
import "./AuthPages.css";

export default function LoginPage() {
  // 가입 전에 픽을 한 번 보여주는 온보딩 퍼널 (docs/Planning.md 4.3).
  // 고정 샘플에서 뽑히며 저장되지 않는다.
  const demo = useMutation({ mutationFn: requestDemoPick });

  return (
    <div className="login-page">
      <h1>메뉴픽</h1>
      <p>오늘 뭐 먹지 고민을 대신 해드립니다.</p>

      <EmailLoginForm />

      <div className="auth-divider">또는</div>

      <div className="login-actions">
        <button onClick={() => (window.location.href = kakaoAuthorizeUrl())}>
          카카오로 로그인
        </button>
        <button onClick={() => (window.location.href = googleAuthorizeUrl())}>
          구글로 로그인
        </button>
      </div>

      <section className="card login-demo">
        <strong>먼저 구경해보기</strong>
        <p className="login-demo-desc">
          로그인 없이 뽑아볼 수 있어요. 샘플 메뉴로 시연하며, 결과는 저장되지 않습니다.
        </p>

        <button disabled={demo.isPending} onClick={() => demo.mutate()}>
          {demo.isPending ? "뽑는 중…" : demo.data ? "다시 뽑기" : "랜덤으로 하나 뽑아보기"}
        </button>

        {demo.data && (
          <div className="login-demo-result">
            <span className="login-demo-name">{demo.data.name}</span>
            <span className="login-demo-categories">{demo.data.categories.join(" · ")}</span>
            <p className="login-demo-cta">
              내 메뉴로 뽑고 기록까지 남기려면 로그인하세요.
            </p>
          </div>
        )}

        {demo.isError && <p className="error">{apiErrorMessage(demo.error)}</p>}
      </section>
    </div>
  );
}

function EmailLoginForm() {
  const navigate = useNavigate();
  const { login } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  const loginMutation = useMutation({
    mutationFn: () => apiLogin(email.trim(), password),
    onSuccess: (accessToken) => {
      login(accessToken);
      navigate("/menus", { replace: true });
    },
  });

  // 가입은 했지만 메일 인증을 안 끝낸 경우. 여기서 빠져나갈 길(재발송)을 주지 않으면
  // 인증 메일을 잃어버린 사용자는 로그인도 재가입도 못 하고 막힌다.
  const needsVerification = apiErrorCode(loginMutation.error) === "EMAIL_NOT_VERIFIED";

  const resendMutation = useMutation({ mutationFn: () => resendVerification(email.trim()) });

  return (
    <form
      className="menu-form"
      onSubmit={(e) => {
        e.preventDefault();
        loginMutation.mutate();
      }}
    >
      <label>
        이메일
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          maxLength={255}
          autoComplete="email"
          required
        />
      </label>
      <label>
        비밀번호
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          required
        />
      </label>

      {loginMutation.isError && (
        <p className="error">{apiErrorMessage(loginMutation.error)}</p>
      )}

      {needsVerification && (
        <p className="auth-notice">
          {resendMutation.isSuccess ? (
            "인증 메일을 다시 보냈어요. 잠시 후에도 안 오면 다시 눌러주세요."
          ) : (
            <button
              type="button"
              className="auth-inline-button"
              disabled={resendMutation.isPending}
              onClick={() => resendMutation.mutate()}
            >
              {resendMutation.isPending ? "보내는 중…" : "인증 메일 다시 보내기"}
            </button>
          )}
        </p>
      )}
      {resendMutation.isError && <p className="error">{apiErrorMessage(resendMutation.error)}</p>}

      <button type="submit" disabled={loginMutation.isPending || !email.trim() || !password}>
        {loginMutation.isPending ? "로그인 중…" : "로그인"}
      </button>

      <div className="auth-links">
        <Link to="/signup">이메일로 가입하기</Link>
        <Link to="/forgot-password">비밀번호를 잊으셨나요?</Link>
      </div>
    </form>
  );
}
