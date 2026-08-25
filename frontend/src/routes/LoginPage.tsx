import { useId, useRef, useState } from "react";
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
import { useFocusOnMount } from "../a11y/useFocusOnMount";
import { apiErrorCode, apiErrorMessage } from "../api/http";
import "./AuthPages.css";

/** 막힌 화면 없이 그냥 로그인한 경우의 착지점 — 메뉴가 없으면 픽 자체가 안 되므로 메뉴부터 보여준다. */
const DEFAULT_LANDING = "/menus";

export default function LoginPage() {
  // 가입 전에 픽을 한 번 보여주는 온보딩 퍼널 (docs/Planning.md 4.3).
  // 고정 샘플에서 뽑히며 저장되지 않는다.
  const demo = useMutation({ mutationFn: requestDemoPick });

  // 이 화면에 어떻게 왔는지를 보내는 쪽이 알려준다.
  //  * socialNotLinked: 연동된 적 없는 소셜 계정으로 로그인을 시도해 콜백 화면에서
  //    되돌아온 경우 (OAuthCallbackPage가 SOCIAL_ACCOUNT_NOT_LINKED를 보고 보낸다).
  //  * sessionExpired: 쓰던 중에 세션이 끊겨 ProtectedRoute가 밀어낸 경우.
  const arrival = useLocation().state as
    | { socialNotLinked?: Provider; sessionExpired?: boolean }
    | null;
  const notLinked = arrival?.socialNotLinked;

  // 만료 안내는 마운트 시점에 이미 내용을 갖고 삽입되므로 라이브 리전으로는 통지되지
  // 않는다(내용과 함께 나타나는 role="status"는 "바뀐 것"이 없어 읽히지 않는다).
  // 남은 수단은 초점을 옮기는 것뿐이다 — 옮기면 스크린리더가 그 문장을 읽고, 키보드
  // 사용자도 이어지는 Tab이 로그인 폼으로 들어간다.
  const expiredNotice = useFocusOnMount<HTMLParagraphElement>();

  // 시연 카드의 제목이자 그 <section>의 접근 가능한 이름.
  const demoHeadingId = useId();

  return (
    <div className="login-page">
      <h1>메뉴픽</h1>
      <p>오늘 뭐 먹지 고민을 대신 해드립니다.</p>

      {/* 아무 말 없이 화면만 바뀌면 사용자는 자기가 뭘 잘못 눌렀다고 생각한다.
          "다시 로그인하면 보던 화면으로 돌아간다"까지 말하는 이유는 ProtectedRoute가
          returnTo를 남겨뒀기 때문이다 — 실제로 그렇게 동작한다.
          tabIndex={-1}이 없으면 <p>는 초점을 받지 못해 focus()가 조용히 무시된다. */}
      {arrival?.sessionExpired && (
        <p className="auth-notice" ref={expiredNotice} tabIndex={-1}>
          로그인한 지 오래되어 자동으로 로그아웃됐어요. 다시 로그인하면 보던 화면으로 돌아갑니다.
        </p>
      )}

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

      <section className="card login-demo" aria-labelledby={demoHeadingId}>
        {/* <strong>이었다. 시각적으로는 이 카드의 제목인데 제목 트리에서는 빠져 있어,
            로그인 화면을 제목으로 훑으면 <h1>메뉴픽 하나만 나오고 "가입 없이 먼저
            써 볼 수 있다"는 이 온보딩 경로는 존재하지 않는 것이 된다. 카드 자체도
            이름이 없어 랜드마크로 잡히지 않았다. */}
        <h2 id={demoHeadingId}>먼저 구경해보기</h2>
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

        {demo.isError && <p className="error" role="alert">{apiErrorMessage(demo.error)}</p>}
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

  // 눌러 보기 전까지는 빈 칸을 오류로 부르지 않는다. 아직 타이핑도 시작하지 않은 화면에
  // 빨간 문구부터 떠 있으면 안내가 아니라 방해다 — 제출을 눌러 막힌 다음부터 이유를 말한다.
  const [submitted, setSubmitted] = useState(false);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const emailEmptyId = useId();
  const passwordEmptyId = useId();

  const emailEmpty = !email.trim();
  const passwordEmpty = !password;
  // 초점은 위에서 아래로 — 화면에서 처음 만나는 빈 칸이 사용자가 먼저 채워야 할 칸이다.
  const firstEmpty = emailEmpty ? emailRef : passwordEmpty ? passwordRef : null;
  const emailError = submitted && emailEmpty;
  const passwordError = submitted && passwordEmpty;

  // 여기서 버튼을 잠그는 것은 "요청이 나가 있다"뿐이다. 미입력으로는 잠그지 않는다 —
  // 잠그는 순간 왜 못 누르는지 말할 자리가 사라진다. 다른 화면(SignupPage 등)은 잠긴
  // 이유가 이미 화면에 떠 있어 버튼에 이어 붙일 수 있지만, 여기는 "아직 안 채웠다"뿐이라
  // 붙일 문구 자체가 없다. 대신 눌렀을 때 무엇이 비었는지 알리고 그 칸으로 초점을 옮긴다.
  const submitBlocked = loginMutation.isPending;

  return (
    <form
      className="menu-form"
      // 브라우저 기본 검증을 끈다. required는 "필수"라는 표시로 남기되, 빈 칸을 알리는 일은
      // 핸들러가 맡는다 — 기본 말풍선은 다음 입력에 사라져 화면에 남지 않고 낭독 여부도
      // 브라우저마다 달라, 오류가 전달됐는지를 이쪽에서 보장할 수 없다.
      noValidate
      // 제출 경로가 버튼 클릭만이 아니다 — 입력칸에서 Enter를 쳐도 여기로 온다.
      // 버튼에서 disabled를 뗀 이상 막는 자리는 클릭 핸들러가 아니라 여기다.
      onSubmit={(e) => {
        e.preventDefault();
        if (submitBlocked) return;
        if (firstEmpty) {
          setSubmitted(true);
          firstEmpty.current?.focus();
          return;
        }
        loginMutation.mutate();
      }}
    >
      <label>
        이메일
        <input
          ref={emailRef}
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          maxLength={255}
          autoComplete="email"
          required
          aria-invalid={emailError || undefined}
          aria-describedby={emailError ? emailEmptyId : undefined}
        />
      </label>
      {/* 이 메시지는 SignupPage의 길이·불일치 안내와 달리 role="alert"를 쓴다. 타이핑 도중이
          아니라 제출을 누른 순간에만 나타나므로 낭독을 가로챌 일이 없고, 오히려 그 순간
          알리지 않으면 초점만 옮겨 가 왜 옮겨졌는지 모른 채 서 있게 된다. */}
      {emailError && (
        <p className="error" role="alert" id={emailEmptyId}>이메일을 입력해주세요.</p>
      )}
      <label>
        비밀번호
        <input
          ref={passwordRef}
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          required
          aria-invalid={passwordError || undefined}
          aria-describedby={passwordError ? passwordEmptyId : undefined}
        />
      </label>
      {passwordError && (
        <p className="error" role="alert" id={passwordEmptyId}>비밀번호를 입력해주세요.</p>
      )}

      {loginMutation.isError && (
        <p className="error" role="alert">{apiErrorMessage(loginMutation.error)}</p>
      )}

      {/* 버튼을 성공 문구로 갈아치우면 방금 누른 요소가 사라져 초점이 <body>로 떨어지고,
          바뀐 문구도 통지되지 않는다. 여기는 이미 로그인에 실패해 막힌 사용자의 마지막
          탈출구라, 눌러도 결과를 알 수 없으면 그대로 무한 재시도가 된다.
          버튼은 그대로 두고 결과만 덧붙인다. 통지용 리전은 마운트 시점부터 비어 있는 채로
          자리를 지켜야 하고(내용과 함께 삽입되는 리전은 통지되지 않는다), 같은 말이 두 번
          읽히지 않도록 보이는 쪽은 감춘다. */}
      {needsVerification && (
        <p className="auth-notice">
          <button
            type="button"
            className="auth-inline-button"
            disabled={resendMutation.isPending}
            onClick={() => resendMutation.mutate()}
          >
            {resendMutation.isPending ? "보내는 중…" : "인증 메일 다시 보내기"}
          </button>
          {resendMutation.isSuccess && (
            <span aria-hidden="true"> 인증 메일을 다시 보냈어요. 잠시 후에도 안 오면 다시 눌러주세요.</span>
          )}
        </p>
      )}
      {needsVerification && (
        <p role="status" className="sr-only">
          {resendMutation.isSuccess ? "인증 메일을 다시 보냈어요. 잠시 후에도 안 오면 다시 눌러주세요." : ""}
        </p>
      )}
      {resendMutation.isError && <p className="error" role="alert">{apiErrorMessage(resendMutation.error)}</p>}

      {/* 누르는 순간 disabled가 걸리면 방금 누른 버튼에서 초점이 <body>로 떨어지고,
          요청이 끝나 다시 활성화돼도 돌아오지 않는다. 그러면 뒤이어 렌더되는 실패 문구도
          "지금 어디에 서 있는지" 모르는 채로 듣게 된다. aria-busy는 초점을 뺏지 않는다.
          진행 중에도 aria-disabled를 함께 건다 — 흐리게 보이고 눌러도 아무 일이 없는데
          "사용 불가"라고 말하지 않으면 보이는 모습과 읽히는 상태가 어긋난다. */}
      <button
        type="submit"
        aria-busy={loginMutation.isPending}
        aria-disabled={submitBlocked || undefined}
      >
        {loginMutation.isPending ? "로그인 중…" : "로그인"}
      </button>

      <div className="auth-links">
        <Link to="/signup">이메일로 가입하기</Link>
        <Link to="/forgot-password">비밀번호를 잊으셨나요?</Link>
      </div>
    </form>
  );
}
