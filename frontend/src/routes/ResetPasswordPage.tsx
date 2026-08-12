import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useMutation } from "@tanstack/react-query";
import { confirmPasswordReset, PASSWORD_MIN_LENGTH } from "../api/auth";
import { useAuth } from "../auth/AuthContext";
import { apiErrorMessage } from "../api/http";
import "./AuthPages.css";

/** 재설정 메일 링크의 착지점. 새 비밀번호를 정하면 그 자리에서 로그인된다. */
export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { login } = useAuth();

  const token = searchParams.get("token");
  const [password, setPassword] = useState("");
  const [passwordCheck, setPasswordCheck] = useState("");

  const confirmMutation = useMutation({
    mutationFn: () => confirmPasswordReset(token!, password),
    onSuccess: (accessToken) => {
      login(accessToken);
      navigate("/menus", { replace: true });
    },
  });

  const tooShort = password.length > 0 && password.length < PASSWORD_MIN_LENGTH;
  const mismatch = passwordCheck.length > 0 && password !== passwordCheck;
  const canSubmit = password.length >= PASSWORD_MIN_LENGTH && !mismatch;

  if (!token) {
    return (
      <div className="login-page">
        <h1>링크가 올바르지 않습니다</h1>
        <div className="auth-links">
          <Link to="/forgot-password">재설정 링크 다시 받기</Link>
        </div>
      </div>
    );
  }

  return (
    <div className="login-page">
      <h1>새 비밀번호 설정</h1>

      <form
        className="menu-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (canSubmit) confirmMutation.mutate();
        }}
      >
        <label>
          새 비밀번호
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
            required
          />
        </label>
        {tooShort && (
          <p className="error">비밀번호는 {PASSWORD_MIN_LENGTH}자 이상이어야 합니다.</p>
        )}

        <label>
          새 비밀번호 확인
          <input
            type="password"
            value={passwordCheck}
            onChange={(e) => setPasswordCheck(e.target.value)}
            autoComplete="new-password"
            required
          />
        </label>
        {mismatch && <p className="error">비밀번호가 서로 다릅니다.</p>}

        {confirmMutation.isError && (
          <>
            <p className="error">{apiErrorMessage(confirmMutation.error)}</p>
            {/* 만료·재사용된 링크는 이 화면에서 되살릴 수 없다 — 다시 받는 길을 준다. */}
            <div className="auth-links">
              <Link to="/forgot-password">재설정 링크 다시 받기</Link>
            </div>
          </>
        )}

        <button type="submit" disabled={confirmMutation.isPending || !canSubmit}>
          {confirmMutation.isPending ? "변경 중…" : "비밀번호 변경"}
        </button>
      </form>
    </div>
  );
}
