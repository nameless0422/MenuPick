import { useId, useState } from "react";
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

  return (
    <div className="login-page">
      <h1>메일을 확인해주세요</h1>
      <section className="card login-demo">
        {/* 발송은 서버가 별도 스레드로 처리해 응답 시점에는 아직 나가지 않았을 수 있다.
            실패해도 화면에 뜨지 않으므로 "보냈다"고 단언하지 않고, 재발송 버튼을 항상 함께 둔다. */}
        <p className="login-demo-desc">
          <strong>{email}</strong> 으로 인증 링크를 보내고 있어요. 링크를 눌러야 로그인할 수 있습니다.
        </p>
        <p className="login-demo-desc">링크는 24시간 동안 유효합니다.</p>

        {resendMutation.isSuccess ? (
          <p className="auth-notice">인증 메일을 다시 보냈어요. 잠시 후에도 안 오면 다시 눌러주세요.</p>
        ) : (
          <button disabled={resendMutation.isPending} onClick={() => resendMutation.mutate()}>
            {resendMutation.isPending ? "보내는 중…" : "메일이 안 왔어요"}
          </button>
        )}
        {resendMutation.isError && <p className="error" role="alert">{apiErrorMessage(resendMutation.error)}</p>}
      </section>

      <div className="auth-links">
        <Link to="/login">로그인으로 돌아가기</Link>
      </div>
    </div>
  );
}
