import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { apiErrorMessage as errorMessage } from "../api/http";
import "./SettingsPage.css";

export default function SettingsPage() {
  const { logout, withdraw } = useAuth();

  // 탈퇴는 되돌리기 어려운 동작이라 버튼 한 번으로 실행되면 안 된다 —
  // 확인 패널을 연 뒤 유예 정책 동의 체크까지 마쳐야 실제 요청이 나간다.
  const [confirming, setConfirming] = useState(false);
  const [agreed, setAgreed] = useState(false);

  const logoutMutation = useMutation({ mutationFn: logout });

  // 성공하면 isAuthenticated가 false가 되고 ProtectedRoute가 /login으로 보낸다
  // (로그아웃과 같은 경로) — 여기서 따로 navigate 하지 않는다.
  const withdrawMutation = useMutation({ mutationFn: withdraw });

  const busy = logoutMutation.isPending || withdrawMutation.isPending;

  return (
    <div className="page">
      <header className="page-header">
        <h2>설정</h2>
      </header>

      <section className="card settings-section">
        <strong>계정</strong>
        <p className="settings-desc">
          이 기기에서 로그아웃합니다. 메뉴·식당·히스토리는 그대로 보관돼요.
        </p>
        <div className="card-actions">
          <button disabled={busy} onClick={() => logoutMutation.mutate()}>
            {logoutMutation.isPending ? "로그아웃 중…" : "로그아웃"}
          </button>
        </div>
        {logoutMutation.isError && <p className="error">{errorMessage(logoutMutation.error)}</p>}
      </section>

      <section className="card settings-section settings-danger">
        <strong>회원 탈퇴</strong>
        <p className="settings-desc">탈퇴하면 계정이 즉시 비활성화되고, 다음 순서로 처리됩니다.</p>
        <ul className="settings-policy">
          <li>탈퇴 후 <strong>30일</strong> 동안은 같은 소셜 계정으로 다시 로그인하면 계정이 복구됩니다.</li>
          <li>30일이 지나면 회원 정보와 메뉴·식당·픽 히스토리가 <strong>모두 삭제</strong>되며 복구할 수 없습니다.</li>
          <li>삭제는 매일 새벽 4시에 자동으로 처리됩니다.</li>
        </ul>

        {confirming ? (
          <div className="settings-confirm">
            <p className="settings-desc">
              정말 탈퇴할까요? 30일이 지나면 되돌릴 수 없습니다.
            </p>
            <label className="settings-agree">
              <input
                type="checkbox"
                checked={agreed}
                onChange={(e) => setAgreed(e.target.checked)}
                disabled={withdrawMutation.isPending}
              />
              위 안내를 확인했고, 탈퇴에 동의합니다.
            </label>
            <div className="card-actions">
              <button
                className="settings-withdraw"
                disabled={!agreed || busy}
                onClick={() => withdrawMutation.mutate()}
              >
                {withdrawMutation.isPending ? "탈퇴 처리 중…" : "탈퇴하기"}
              </button>
              <button
                type="button"
                disabled={withdrawMutation.isPending}
                onClick={() => {
                  setConfirming(false);
                  setAgreed(false);
                }}
              >
                취소
              </button>
            </div>
          </div>
        ) : (
          <div className="card-actions">
            <button type="button" disabled={busy} onClick={() => setConfirming(true)}>
              회원 탈퇴
            </button>
          </div>
        )}

        {withdrawMutation.isError && <p className="error">{errorMessage(withdrawMutation.error)}</p>}
      </section>
    </div>
  );
}
