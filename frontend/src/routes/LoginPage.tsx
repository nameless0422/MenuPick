import { useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { useMutation } from "@tanstack/react-query";
import { loginAuthorizeUrl } from "../auth/oauthUrls";
import { requestDemoPick } from "../api/pick";
import {
  login as apiLogin,
  resendVerification,
  PROVIDERS,
  PROVIDER_LABELS,
  type Provider,
} from "../api/auth";
import { useAuth } from "../auth/AuthContext";
import { consumeReturnTo } from "../auth/returnTo";
import { apiErrorCode, apiErrorMessage } from "../api/http";
import "./AuthPages.css";

/** 막힌 화면 없이 그냥 로그인한 경우의 착지점 — 메뉴가 없으면 픽 자체가 안 되므로 메뉴부터 보여준다. */
const DEFAULT_LANDING = "/menus";

export default function LoginPage() {
  // 가입 전에 픽을 한 번 보여주는 온보딩 퍼널 (docs/Planning.md 4.3).
  // 고정 샘플에서 뽑히며 저장되지 않는다.
  const demo = useMutation({ mutationFn: requestDemoPick });

  // 연동된 적 없는 소셜 계정으로 로그인을 시도해 콜백 화면에서 되돌아온 경우
  // (OAuthCallbackPage가 SOCIAL_ACCOUNT_NOT_LINKED를 보고 여기로 보낸다).
  const notLinked = (useLocation().state as { socialNotLinked?: Provider } | null)?.socialNotLinked;

  return (
    <div className="login-page">
      <h1>메뉴픽</h1>
      <p>오늘 뭐 먹지 고민을 대신 해드립니다.</p>

      <EmailLoginForm />

      <div className="auth-divider">또는</div>

      {notLinked && (
        <p className="auth-notice" role="status">
          {PROVIDER_LABELS[notLinked]} 계정이 아직 연동돼 있지 않아요.{" "}
          <Link to="/signup">이메일로 가입</Link>한 뒤 설정에서 연동해주세요.
        </p>
      )}

      <div className="login-actions">
        {PROVIDERS.map((provider) => (
          <button
            key={provider}
            onClick={() => (window.location.href = loginAuthorizeUrl(provider))}
          >
            {PROVIDER_LABELS[provider]}로 로그인
          </button>
        ))}
      </div>

      {/* 소셜은 가입 경로가 아니다. 이 줄이 없으면 "카카오로 로그인"을 처음 누른 사람은
          가입 버튼으로 읽고, 거절당한 뒤에야 이유를 알게 된다. */}
      <p className="login-social-hint">
        소셜 로그인은 이메일로 가입한 계정에 <strong>설정 &gt; 소셜 계정 연동</strong>을 마친 뒤
        쓸 수 있어요.
      </p>

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
      navigate(consumeReturnTo(DEFAULT_LANDING), { replace: true });
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
