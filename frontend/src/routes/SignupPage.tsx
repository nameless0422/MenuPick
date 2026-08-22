import { useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation } from "@tanstack/react-query";
import { signup, resendVerification, PASSWORD_MIN_LENGTH, PASSWORD_MAX_LENGTH } from "../api/auth";
import { apiErrorMessage } from "../api/http";
import "./AuthPages.css";

export default function SignupPage() {
  const [email, setEmail] = useState("");
  const [nickname, setNickname] = useState("");
  const [password, setPassword] = useState("");
  const [passwordCheck, setPasswordCheck] = useState("");

  const signupMutation = useMutation({
    mutationFn: () => signup(email.trim(), password, nickname.trim()),
  });

  // 서버에 보내기 전에 잡아야 하는 것만 여기서 본다. 나머지(형식·중복)는 서버 응답을 그대로 보여준다.
  const tooShort = password.length > 0 && password.length < PASSWORD_MIN_LENGTH;
  const mismatch = passwordCheck.length > 0 && password !== passwordCheck;
  // 검증 메시지는 role="alert"를 쓰지 않는다. 타이핑하는 동안 나타났다 사라지므로
  // 글자마다 낭독을 가로채 오히려 방해가 된다. 대신 aria-describedby로 필드에 묶어
  // 두면, 그 칸으로 이동했을 때 이름 뒤에 이유가 함께 읽힌다.
  const tooShortId = useId();
  const mismatchId = useId();
  const canSubmit =
    email.trim() && nickname.trim() && password.length >= PASSWORD_MIN_LENGTH && !mismatch;

  if (signupMutation.isSuccess) {
    return <VerificationSent email={email.trim()} />;
  }

  return (
    <div className="login-page">
      <h1>이메일로 가입</h1>

      <form
        className="menu-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (canSubmit) signupMutation.mutate();
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
          닉네임
          <input
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            maxLength={50}
            autoComplete="nickname"
            required
          />
        </label>

        <label>
          비밀번호
          <input
            type="password"
            maxLength={PASSWORD_MAX_LENGTH}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
            required
            aria-invalid={tooShort || undefined}
            aria-describedby={tooShort ? tooShortId : undefined}
          />
        </label>
        {tooShort && (
          <p className="error" id={tooShortId}>비밀번호는 {PASSWORD_MIN_LENGTH}자 이상이어야 합니다.</p>
        )}

        <label>
          비밀번호 확인
          <input
            type="password"
            maxLength={PASSWORD_MAX_LENGTH}
            value={passwordCheck}
            onChange={(e) => setPasswordCheck(e.target.value)}
            autoComplete="new-password"
            required
            aria-invalid={mismatch || undefined}
            aria-describedby={mismatch ? mismatchId : undefined}
          />
        </label>
        {mismatch && <p className="error" id={mismatchId}>비밀번호가 서로 다릅니다.</p>}

        {signupMutation.isError && <p className="error" role="alert">{apiErrorMessage(signupMutation.error)}</p>}

        <button type="submit" disabled={signupMutation.isPending || !canSubmit}>
          {signupMutation.isPending ? "가입 중…" : "가입하기"}
        </button>

        <div className="auth-links">
          <Link to="/login">이미 계정이 있어요</Link>
        </div>
      </form>
    </div>
  );
}

/** 가입 직후 화면. 인증을 마쳐야 로그인되므로 다음 할 일을 분명히 알려준다. */
function VerificationSent({ email }: { email: string }) {
  const resendMutation = useMutation({ mutationFn: () => resendVerification(email) });
  const headingRef = useRef<HTMLHeadingElement>(null);

  // 가입에 성공하면 폼이 통째로 이 화면으로 갈린다 — 방금 누른 "가입하기" 버튼이 사라져
  // 브라우저가 초점을 <body>로 되돌리고, 그때부터 Tab은 페이지 맨 위로 간다.
  // 이 화면은 "메일을 확인해야 로그인된다"는 다음 행동 지시를 담고 있다. 인증을 마쳐야
  // 로그인이 되는 구조(auth.ts의 EMAIL_NOT_VERIFIED)라, 이 안내를 놓치면 가입 직후
  // 로그인 실패 루프에 빠진다. 제목으로 초점을 옮겨 화면이 바뀐 사실부터 알린다.
  useEffect(() => {
    headingRef.current?.focus();
  }, []);

  return (
    <div className="login-page">
      <h1 ref={headingRef} tabIndex={-1}>메일을 확인해주세요</h1>
      <section className="card login-demo">
        {/* 발송은 서버가 별도 스레드로 처리해 응답 시점에는 아직 나가지 않았을 수 있다.
            실패해도 화면에 뜨지 않으므로 "보냈다"고 단언하지 않고, 재발송 버튼을 항상 함께 둔다. */}
        <p className="login-demo-desc">
          <strong>{email}</strong> 으로 인증 링크를 보내고 있어요. 링크를 눌러야 로그인할 수 있습니다.
        </p>
        <p className="login-demo-desc">링크는 24시간 동안 유효합니다.</p>

        {/* 버튼을 성공 문구로 갈아치우면 방금 누른 요소가 사라져 초점이 <body>로 떨어지고,
            바뀐 문구도 통지되지 않는다. 버튼은 그대로 두고 결과만 덧붙인다.
            보이는 문구와 통지를 나눈다 — 통지용 리전은 마운트 시점부터 비어 있는 채로
            자리를 지켜야 하고(내용과 함께 삽입되는 리전은 통지되지 않는다), 같은 말이
            두 번 읽히지 않도록 보이는 쪽은 감춘다. */}
        <button disabled={resendMutation.isPending} onClick={() => resendMutation.mutate()}>
          {resendMutation.isPending ? "보내는 중…" : "메일이 안 왔어요"}
        </button>
        {resendMutation.isSuccess && (
          <p className="auth-notice" aria-hidden="true">
            인증 메일을 다시 보냈어요. 잠시 후에도 안 오면 다시 눌러주세요.
          </p>
        )}
        <p role="status" className="sr-only">
          {resendMutation.isSuccess ? "인증 메일을 다시 보냈어요. 잠시 후에도 안 오면 다시 눌러주세요." : ""}
        </p>
        {resendMutation.isError && <p className="error" role="alert">{apiErrorMessage(resendMutation.error)}</p>}
      </section>

      <div className="auth-links">
        <Link to="/login">로그인으로 돌아가기</Link>
      </div>
    </div>
  );
}
