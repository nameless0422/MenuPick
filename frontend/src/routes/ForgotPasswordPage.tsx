import { useEffect, useRef, useState } from "react";
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
          onSubmit={(e) => {
            e.preventDefault();
            if (email.trim()) resetMutation.mutate();
          }}
        >
          <p className="login-demo-desc">
            가입할 때 쓴 이메일을 입력하면 재설정 링크를 보내드립니다.
          </p>
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

          {resetMutation.isError && <p className="error" role="alert">{apiErrorMessage(resetMutation.error)}</p>}

          <button type="submit" disabled={resetMutation.isPending || !email.trim()}>
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
