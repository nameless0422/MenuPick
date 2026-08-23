import { useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation } from "@tanstack/react-query";
import { requestPasswordReset } from "../api/auth";
import { apiErrorMessage } from "../api/http";
import "./AuthPages.css";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");

  const resetMutation = useMutation({ mutationFn: () => requestPasswordReset(email.trim()) });

  // 발송에 성공하면 폼이 통째로 안내 화면으로 갈린다 — 방금 누른 버튼이 사라져 초점이
  // <body>로 떨어진다. <h1>은 "비밀번호 재설정" 그대로라 제목만으로는 화면이 바뀐 것을
  // 알 방법조차 없다. 그래서 안내 화면에 제목을 따로 두고 그리로 초점을 옮긴다.
  const sentHeading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (resetMutation.isSuccess) sentHeading.current?.focus();
  }, [resetMutation.isSuccess]);

  // 눌러 보기 전까지는 빈 칸을 오류로 부르지 않는다. 아직 타이핑도 시작하지 않은 화면에
  // 빨간 문구부터 떠 있으면 안내가 아니라 방해다 — 제출을 눌러 막힌 다음부터 이유를 말한다.
  const [submitted, setSubmitted] = useState(false);
  const emailRef = useRef<HTMLInputElement>(null);
  const emailEmptyId = useId();

  const emailEmpty = !email.trim();
  const emailError = submitted && emailEmpty;

  // 버튼을 잠그는 것은 "요청이 나가 있다"뿐이다. 미입력으로는 잠그지 않는다 — 이 화면에서
  // 막히는 이유는 "아직 안 채웠다" 하나뿐이고 그에 해당하는 문구가 화면에 없어, 잠가 버리면
  // 버튼에 이어 붙일 사유 자체가 없다. 대신 눌렀을 때 알리고 그 칸으로 초점을 옮긴다.
  const submitBlocked = resetMutation.isPending;

  return (
    <div className="login-page">
      <h1>비밀번호 재설정</h1>

      {resetMutation.isSuccess ? (
        <section className="card login-demo">
          <h2 ref={sentHeading} tabIndex={-1}>메일을 확인해주세요</h2>
          {/* 서버는 가입 여부와 무관하게 성공을 준다(계정 존재 여부를 흘리지 않기 위해).
              화면 문구도 "보냈다"가 아니라 "가입돼 있다면 보냈다"여야 사실과 맞는다. */}
          <p className="login-demo-desc">
            <strong>{email.trim()}</strong> 으로 가입된 계정이 있다면 재설정 링크를 보냈습니다.
          </p>
          <p className="login-demo-desc">링크는 30분 동안 유효하며 한 번만 사용할 수 있어요.</p>
          <div className="auth-links">
            <Link to="/login">로그인으로 돌아가기</Link>
          </div>
        </section>
      ) : (
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
            if (emailEmpty) {
              setSubmitted(true);
              emailRef.current?.focus();
              return;
            }
            resetMutation.mutate();
          }}
        >
          <p className="login-demo-desc">
            가입할 때 쓴 이메일을 입력하면 재설정 링크를 보내드립니다.
          </p>
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
          {/* 타이핑 도중이 아니라 제출을 누른 순간에만 나타나므로 role="alert"가 맞다.
              여기서 알리지 않으면 초점만 이 칸으로 옮겨 가 왜 옮겨졌는지 알 수 없다. */}
          {emailError && (
            <p className="error" role="alert" id={emailEmptyId}>이메일을 입력해주세요.</p>
          )}

          {resetMutation.isError && <p className="error" role="alert">{apiErrorMessage(resetMutation.error)}</p>}

          {/* 누르는 순간 disabled가 걸리면 방금 누른 버튼에서 초점이 <body>로 떨어지고,
              요청이 끝나 다시 활성화돼도 돌아오지 않는다. aria-busy는 초점을 뺏지 않는다.
              진행 중에도 aria-disabled를 함께 건다 — 흐리게 보이고 눌러도 아무 일이 없는데
              "사용 불가"라고 말하지 않으면 보이는 모습과 읽히는 상태가 어긋난다. */}
          <button
            type="submit"
            aria-busy={resetMutation.isPending}
            aria-disabled={submitBlocked || undefined}
          >
            {resetMutation.isPending ? "보내는 중…" : "재설정 링크 받기"}
          </button>

          <div className="auth-links">
            <Link to="/login">로그인으로 돌아가기</Link>
          </div>
        </form>
      )}
    </div>
  );
}
