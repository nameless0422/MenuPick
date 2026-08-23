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

  // 눌러 보기 전까지는 빈 칸을 오류로 부르지 않는다. 아직 타이핑도 시작하지 않은 화면에
  // 빨간 문구부터 떠 있으면 안내가 아니라 방해다 — 제출을 눌러 막힌 다음부터 이유를 말한다.
  const [submitted, setSubmitted] = useState(false);
  const emailRef = useRef<HTMLInputElement>(null);
  const nicknameRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const passwordCheckRef = useRef<HTMLInputElement>(null);
  const emailEmptyId = useId();
  const nicknameEmptyId = useId();
  const passwordEmptyId = useId();
  const passwordCheckEmptyId = useId();

  // "비었다"와 "형식이 틀렸다"를 따로 다룬다. 형식 오류(길이·불일치)는 이미 화면에 문구가
  // 떠 있어 버튼에 그대로 이어 붙일 수 있지만, 빈 칸에는 붙일 문구 자체가 없다 — 그래서
  // 빈 칸으로는 버튼을 잠그지 않고, 눌렀을 때 무엇이 비었는지 알리고 그 칸으로 초점을 옮긴다.
  const emailEmpty = !email.trim();
  const nicknameEmpty = !nickname.trim();
  const passwordEmpty = password.length === 0;
  // 확인 칸의 빈 상태는 mismatch로 잡히지 않는다(둘째 칸을 아예 안 건드리면 불일치가 아니다).
  // 여기서 따로 보지 않으면 확인 없이 그대로 가입 요청이 나간다.
  const passwordCheckEmpty = passwordCheck.length === 0;
  // 초점은 위에서 아래로 — 화면에서 처음 만나는 빈 칸이 사용자가 먼저 채워야 할 칸이다.
  const firstEmpty = emailEmpty
    ? emailRef
    : nicknameEmpty
      ? nicknameRef
      : passwordEmpty
        ? passwordRef
        : passwordCheckEmpty
          ? passwordCheckRef
          : null;

  // "요청이 나가 있다"와 "형식이 틀렸다"만 버튼을 잠근다. 앞은 잠깐 기다리면 풀리는 진행
  // 상태라 aria-busy로 충분하고, 뒤는 무엇을 고쳐야 하는지가 이미 문구로 떠 있다.
  const submitBlocked = signupMutation.isPending || tooShort || mismatch;

  // 잠긴 이유는 이미 각 칸 아래에 떠 있다 — 버튼 전용 문구를 새로 만들지 않고 그 메시지를
  // 버튼에도 이어 붙인다. 초점이 버튼에 닿는 순간 이름 뒤로 이유가 함께 읽힌다.
  const blockedReasonIds =
    [tooShort ? tooShortId : "", mismatch ? mismatchId : ""].filter(Boolean).join(" ") ||
    undefined;

  if (signupMutation.isSuccess) {
    return <VerificationSent email={email.trim()} />;
  }

  return (
    <div className="login-page">
      <h1>이메일로 가입</h1>

      <form
        className="menu-form"
        // 브라우저 기본 검증을 끈다. required는 "필수"라는 표시로 남기되, 빈 칸을 알리는 일은
        // 핸들러가 맡는다 — 기본 말풍선은 다음 입력에 사라져 화면에 남지 않고 낭독 여부도
        // 브라우저마다 달라, 오류가 전달됐는지를 이쪽에서 보장할 수 없다.
        // 형식(이메일 모양·중복)은 그대로 서버 응답에 맡긴다 — 이 화면의 원래 방침이다.
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
          signupMutation.mutate();
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
            aria-invalid={(submitted && emailEmpty) || undefined}
            aria-describedby={submitted && emailEmpty ? emailEmptyId : undefined}
          />
        </label>
        {/* 빈 칸 안내는 길이·불일치 안내와 달리 role="alert"를 쓴다. 타이핑 도중이 아니라
            제출을 누른 순간에만 나타나므로 낭독을 가로챌 일이 없고, 오히려 그 순간 알리지
            않으면 초점만 옮겨 가 왜 옮겨졌는지 모른 채 서 있게 된다. */}
        {submitted && emailEmpty && (
          <p className="error" role="alert" id={emailEmptyId}>이메일을 입력해주세요.</p>
        )}

        <label>
          닉네임
          <input
            ref={nicknameRef}
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            maxLength={50}
            autoComplete="nickname"
            required
            aria-invalid={(submitted && nicknameEmpty) || undefined}
            aria-describedby={submitted && nicknameEmpty ? nicknameEmptyId : undefined}
          />
        </label>
        {submitted && nicknameEmpty && (
          <p className="error" role="alert" id={nicknameEmptyId}>닉네임을 입력해주세요.</p>
        )}

        <label>
          비밀번호
          <input
            ref={passwordRef}
            type="password"
            maxLength={PASSWORD_MAX_LENGTH}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
            required
            // 길이 미달과 빈 칸은 동시에 성립하지 않는다(tooShort는 한 글자 이상일 때만 참).
            aria-invalid={tooShort || (submitted && passwordEmpty) || undefined}
            aria-describedby={
              tooShort ? tooShortId : submitted && passwordEmpty ? passwordEmptyId : undefined
            }
          />
        </label>
        {tooShort && (
          <p className="error" id={tooShortId}>비밀번호는 {PASSWORD_MIN_LENGTH}자 이상이어야 합니다.</p>
        )}
        {submitted && passwordEmpty && (
          <p className="error" role="alert" id={passwordEmptyId}>비밀번호를 입력해주세요.</p>
        )}

        <label>
          비밀번호 확인
          <input
            ref={passwordCheckRef}
            type="password"
            maxLength={PASSWORD_MAX_LENGTH}
            value={passwordCheck}
            onChange={(e) => setPasswordCheck(e.target.value)}
            autoComplete="new-password"
            required
            aria-invalid={mismatch || (submitted && passwordCheckEmpty) || undefined}
            aria-describedby={
              mismatch
                ? mismatchId
                : submitted && passwordCheckEmpty
                  ? passwordCheckEmptyId
                  : undefined
            }
          />
        </label>
        {mismatch && <p className="error" id={mismatchId}>비밀번호가 서로 다릅니다.</p>}
        {submitted && passwordCheckEmpty && (
          <p className="error" role="alert" id={passwordCheckEmptyId}>
            비밀번호를 한 번 더 입력해주세요.
          </p>
        )}

        {signupMutation.isError && <p className="error" role="alert">{apiErrorMessage(signupMutation.error)}</p>}

        {/* 누르는 순간 disabled가 걸리면 방금 누른 버튼에서 초점이 <body>로 떨어지고,
            요청이 끝나 다시 활성화돼도 돌아오지 않는다. aria-busy는 초점을 뺏지 않는다.
            진행 중에도 aria-disabled를 함께 건다 — 흐리게 보이고 눌러도 아무 일이 없는데
            "사용 불가"라고 말하지 않으면 보이는 모습과 읽히는 상태가 어긋난다. */}
        <button
          type="submit"
          aria-busy={signupMutation.isPending}
          aria-disabled={submitBlocked || undefined}
          aria-describedby={blockedReasonIds}
        >
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
