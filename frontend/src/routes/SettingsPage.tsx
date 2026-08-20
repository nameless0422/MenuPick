import { useState } from "react";
import { useLocation } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import {
  changePassword,
  fetchMe,
  unlinkSocialAccount,
  PASSWORD_MIN_LENGTH,
  PASSWORD_MAX_LENGTH,
  PROVIDERS,
  PROVIDER_LABELS,
  type Me,
  type Provider,
} from "../api/auth";
import { linkAuthorizeUrl } from "../auth/oauthUrls";
import { apiErrorMessage as errorMessage } from "../api/http";
import "./SettingsPage.css";

export default function SettingsPage() {
  const { logout, withdraw } = useAuth();

  const meQuery = useQuery({ queryKey: ["me"], queryFn: fetchMe });

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
        <h1>설정</h1>
      </header>

      <section className="card settings-section">
        <strong>계정</strong>
        {meQuery.isSuccess && (
          <p className="settings-desc">
            {meQuery.data.nickname}
            {meQuery.data.email && ` · ${meQuery.data.email}`}
          </p>
        )}
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

      {/* 연동 상태를 모르면 "연동"과 "해제" 중 무엇을 그릴지 정할 수 없다 — /me를 받은 뒤에 그린다. */}
      {meQuery.isSuccess && <SocialLinkSection me={meQuery.data} />}

      {/* 소셜 전용 계정에는 바꿀 비밀번호가 없다 — 폼을 띄워봐야 누르는 순간 400이 날 뿐이다. */}
      {meQuery.data?.hasPassword && <PasswordSection />}

      <section className="card settings-section settings-danger">
        <strong>회원 탈퇴</strong>
        <p className="settings-desc">탈퇴하면 계정이 즉시 비활성화되고, 다음 순서로 처리됩니다.</p>
        <ul className="settings-policy">
          <li>탈퇴 후 <strong>30일</strong> 동안은 같은 계정으로 다시 로그인하면 복구됩니다.</li>
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

/**
 * 소셜 계정 연동 관리.
 *
 * <p>소셜은 가입 경로가 아니라 이미 있는 계정에 붙이는 부가 수단이다(백엔드 AuthService 참고).
 * 그래서 연동을 시작할 수 있는 자리가 여기 하나뿐이고, 여기가 비면 소셜 로그인은 영영
 * 쓸 수 없게 된다.
 */
function SocialLinkSection({ me }: { me: Me }) {
  const queryClient = useQueryClient();

  // 연동을 마친 콜백 화면이 여기로 보내면서 남긴 표시. 화면이 그냥 바뀌기만 하면
  // 사용자는 방금 누른 것이 먹혔는지 알 수 없다.
  const justLinked = (useLocation().state as { socialLinked?: Provider } | null)?.socialLinked;

  const unlinkMutation = useMutation({
    // 함수를 그대로 넘기지 않는다 — react-query는 mutationFn을 (변수, 컨텍스트)로 부르고,
    // unlinkSocialAccount의 두 번째 인자가 언젠가 생기면 그 컨텍스트가 조용히 들어간다.
    mutationFn: (provider: Provider) => unlinkSocialAccount(provider),
    // 서버가 해제 후의 전체 목록을 그대로 준다 — /me를 다시 부르지 않고 캐시만 맞춘다.
    // invalidate로 두면 재조회가 끝날 때까지 방금 끊은 항목이 "연동됨"으로 남아 있다.
    onSuccess: (linkedProviders) => {
      queryClient.setQueryData<Me>(["me"], (prev) =>
        prev ? { ...prev, linkedProviders } : prev,
      );
    },
  });

  return (
    <section className="card settings-section">
      <strong>소셜 계정 연동</strong>
      <p className="settings-desc">
        연동하면 다음부터 그 계정으로도 로그인할 수 있어요. 가입은 이메일로만 받고 있어,
        연동하지 않은 소셜 계정으로는 로그인되지 않습니다.
      </p>

      {justLinked && (
        <p className="settings-desc" role="status">
          {PROVIDER_LABELS[justLinked]} 계정을 연동했습니다.
        </p>
      )}

      <ul className="settings-links">
        {PROVIDERS.map((provider) => (
          <SocialLinkRow
            key={provider}
            provider={provider}
            me={me}
            busy={unlinkMutation.isPending}
            onUnlink={() => unlinkMutation.mutate(provider)}
          />
        ))}
      </ul>

      {unlinkMutation.isError && <p className="error">{errorMessage(unlinkMutation.error)}</p>}
    </section>
  );
}

function SocialLinkRow({
  provider,
  me,
  busy,
  onUnlink,
}: {
  provider: Provider;
  me: Me;
  busy: boolean;
  onUnlink: () => void;
}) {
  const linked = me.linkedProviders.includes(provider);

  // 서버가 최종 판단하지만(LAST_LOGIN_METHOD), 누르면 반드시 실패하는 버튼을 열어두면
  // 사용자는 왜 안 되는지 모른 채 에러만 본다. 판정 기준은 백엔드 AuthService.unlink와 같다:
  // "해제한 뒤에도 들어올 문이 하나는 남는가". 비밀번호가 있거나, 다른 소셜 연동이 남으면 된다.
  const lastLoginMethod = linked && !me.hasPassword && me.linkedProviders.length === 1;

  return (
    <li className="settings-link-row">
      {/* 제공자 이름과 상태를 각각 제 요소에 둔다 — 한 덩어리로 묶으면 화면에서도
          "카카오연동됨"으로 붙어 읽히고 스크린리더도 한 문장으로 흘려 읽는다. */}
      <span className="settings-link-name">{PROVIDER_LABELS[provider]}</span>
      <span className="settings-link-state">{linked ? "연동됨" : "연동 안 됨"}</span>

      {/* 버튼 이름에 제공자를 넣는다. 한 화면에 제공자 수만큼 놓이므로 "연동하기"만으로는
          스크린리더 사용자가 어느 쪽 버튼인지 구분할 수 없다. */}
      {linked ? (
        <button type="button" disabled={busy || lastLoginMethod} onClick={onUnlink}>
          {PROVIDER_LABELS[provider]} 연동 해제
        </button>
      ) : (
        // 로그인과 같은 인가 흐름을 타되 모드만 다르다 — 브라우저를 통째로 제공자로 보낸다.
        <button
          type="button"
          disabled={busy}
          onClick={() => (window.location.href = linkAuthorizeUrl(provider))}
        >
          {PROVIDER_LABELS[provider]} 연동하기
        </button>
      )}

      {lastLoginMethod && (
        <p className="settings-desc settings-link-note">
          마지막 로그인 수단이라 해제할 수 없어요. 비밀번호를 먼저 설정해주세요.
        </p>
      )}
    </li>
  );
}

function PasswordSection() {
  const { login } = useAuth();

  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [nextCheck, setNextCheck] = useState("");

  const changeMutation = useMutation({
    mutationFn: () => changePassword(current, next),
    // 서버가 세션을 새로 발급한다(다른 기기는 로그아웃된다). 새 Access Token을 받아두지 않으면
    // 지금 보고 있는 이 화면부터 다음 요청에서 튕긴다.
    onSuccess: (accessToken) => {
      login(accessToken);
      setCurrent("");
      setNext("");
      setNextCheck("");
    },
  });

  const tooShort = next.length > 0 && next.length < PASSWORD_MIN_LENGTH;
  const mismatch = nextCheck.length > 0 && next !== nextCheck;
  const canSubmit = current.length > 0 && next.length >= PASSWORD_MIN_LENGTH && !mismatch;

  return (
    <section className="card settings-section">
      <strong>비밀번호 변경</strong>
      <p className="settings-desc">
        변경하면 다른 기기의 로그인이 모두 해제됩니다.
      </p>

      <form
        className="menu-form settings-password-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (canSubmit) changeMutation.mutate();
        }}
      >
        <label>
          현재 비밀번호
          <input
            type="password"
            maxLength={PASSWORD_MAX_LENGTH}
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        <label>
          새 비밀번호
          <input
            type="password"
            maxLength={PASSWORD_MAX_LENGTH}
            value={next}
            onChange={(e) => setNext(e.target.value)}
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
            maxLength={PASSWORD_MAX_LENGTH}
            value={nextCheck}
            onChange={(e) => setNextCheck(e.target.value)}
            autoComplete="new-password"
            required
          />
        </label>
        {mismatch && <p className="error">비밀번호가 서로 다릅니다.</p>}

        {changeMutation.isError && <p className="error">{errorMessage(changeMutation.error)}</p>}
        {changeMutation.isSuccess && (
          <p className="settings-desc" role="status">비밀번호를 변경했습니다.</p>
        )}

        <div className="card-actions">
          <button type="submit" disabled={changeMutation.isPending || !canSubmit}>
            {changeMutation.isPending ? "변경 중…" : "비밀번호 변경"}
          </button>
        </div>
      </form>
    </section>
  );
}
