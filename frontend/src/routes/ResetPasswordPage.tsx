import { useId, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useMutation } from "@tanstack/react-query";
import { confirmPasswordReset, PASSWORD_MIN_LENGTH, PASSWORD_MAX_LENGTH } from "../api/auth";
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
  // 검증 메시지는 role="alert"를 쓰지 않는다. 타이핑하는 동안 나타났다 사라지므로
  // 글자마다 낭독을 가로채 오히려 방해가 된다. 대신 aria-describedby로 필드에 묶어
  // 두면, 그 칸으로 이동했을 때 이름 뒤에 이유가 함께 읽힌다.
  const tooShortId = useId();
  const mismatchId = useId();

  // 눌러 보기 전까지는 빈 칸을 오류로 부르지 않는다. 아직 타이핑도 시작하지 않은 화면에
  // 빨간 문구부터 떠 있으면 안내가 아니라 방해다 — 제출을 눌러 막힌 다음부터 이유를 말한다.
  const [submitted, setSubmitted] = useState(false);
  const passwordRef = useRef<HTMLInputElement>(null);
  const passwordCheckRef = useRef<HTMLInputElement>(null);
  const passwordEmptyId = useId();
  const passwordCheckEmptyId = useId();

  // "비었다"와 "형식이 틀렸다"를 따로 다룬다. 형식 오류(길이·불일치)는 이미 화면에 문구가
  // 떠 있어 버튼에 그대로 이어 붙일 수 있지만, 빈 칸에는 붙일 문구 자체가 없다 — 그래서
  // 빈 칸으로는 버튼을 잠그지 않고, 눌렀을 때 무엇이 비었는지 알리고 그 칸으로 초점을 옮긴다.
  const passwordEmpty = password.length === 0;
  // 확인 칸의 빈 상태는 mismatch로 잡히지 않는다(둘째 칸을 아예 안 건드리면 불일치가 아니다).
  // 여기서 따로 보지 않으면 확인 없이 그대로 비밀번호가 바뀐다.
  const passwordCheckEmpty = passwordCheck.length === 0;
  // 초점은 위에서 아래로 — 화면에서 처음 만나는 빈 칸이 사용자가 먼저 채워야 할 칸이다.
  const firstEmpty = passwordEmpty ? passwordRef : passwordCheckEmpty ? passwordCheckRef : null;

  // "요청이 나가 있다"와 "형식이 틀렸다"만 버튼을 잠근다. 앞은 잠깐 기다리면 풀리는 진행
  // 상태라 aria-busy로 충분하고, 뒤는 무엇을 고쳐야 하는지가 이미 문구로 떠 있다.
  const submitBlocked = confirmMutation.isPending || tooShort || mismatch;

  // 잠긴 이유는 이미 각 칸 아래에 떠 있다 — 버튼 전용 문구를 새로 만들지 않고 그 메시지를
  // 버튼에도 이어 붙인다. 초점이 버튼에 닿는 순간 이름 뒤로 이유가 함께 읽힌다.
  const blockedReasonIds =
    [tooShort ? tooShortId : "", mismatch ? mismatchId : ""].filter(Boolean).join(" ") ||
    undefined;

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
          confirmMutation.mutate();
        }}
      >
        <label>
          새 비밀번호
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
        {/* 빈 칸 안내는 길이·불일치 안내와 달리 role="alert"를 쓴다. 타이핑 도중이 아니라
            제출을 누른 순간에만 나타나므로 낭독을 가로챌 일이 없고, 오히려 그 순간 알리지
            않으면 초점만 옮겨 가 왜 옮겨졌는지 모른 채 서 있게 된다. */}
        {submitted && passwordEmpty && (
          <p className="error" role="alert" id={passwordEmptyId}>새 비밀번호를 입력해주세요.</p>
        )}

        <label>
          새 비밀번호 확인
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
            새 비밀번호를 한 번 더 입력해주세요.
          </p>
        )}

        {confirmMutation.isError && (
          <>
            <p className="error" role="alert">{apiErrorMessage(confirmMutation.error)}</p>
            {/* 만료·재사용된 링크는 이 화면에서 되살릴 수 없다 — 다시 받는 길을 준다. */}
            <div className="auth-links">
              <Link to="/forgot-password">재설정 링크 다시 받기</Link>
            </div>
          </>
        )}

        {/* 누르는 순간 disabled가 걸리면 방금 누른 버튼에서 초점이 <body>로 떨어지고,
            요청이 끝나 다시 활성화돼도 돌아오지 않는다. aria-busy는 초점을 뺏지 않는다.
            진행 중에도 aria-disabled를 함께 건다 — 흐리게 보이고 눌러도 아무 일이 없는데
            "사용 불가"라고 말하지 않으면 보이는 모습과 읽히는 상태가 어긋난다. */}
        <button
          type="submit"
          aria-busy={confirmMutation.isPending}
          aria-disabled={submitBlocked || undefined}
          aria-describedby={blockedReasonIds}
        >
          {confirmMutation.isPending ? "변경 중…" : "비밀번호 변경"}
        </button>
      </form>
    </div>
  );
}
