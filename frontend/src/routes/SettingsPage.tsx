import { useCallback, useEffect, useId, useRef, useState } from "react";
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

  // 확인 패널은 "회원 탈퇴" 버튼을 통째로 대체한다 — 방금 누른 버튼이 사라져 초점이
  // <body>로 떨어지고, 되돌릴 수 없는 동작의 확인 패널이 열린 사실 자체가 전달되지 않는다.
  // 취소도 마찬가지로 패널이 사라지며 초점을 잃는다. 이 처리는 MenusPage·RestaurantsPage에
  // 이미 있는 것과 같다 — 이 화면만 빠져 있었다.
  const confirmPanel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (confirming) confirmPanel.current?.focus();
  }, [confirming]);

  // 취소하면 "회원 탈퇴" 버튼이 새로 마운트된다 — 닫기 전에 잡아 둔 참조로는 갈 수 없고,
  // 다시 그려지는 순간을 기다려야 한다. 그 "순간"을 잡는 자리가 곧 ref 콜백이다:
  // React가 요소를 붙이면서 직접 불러주므로 state를 켰다 effect에서 다시 끄는 왕복도,
  // 그 때문에 도는 렌더 한 번도 필요 없다. 플래그는 렌더 결과와 무관하니 ref로 둔다.
  const focusOpenerAfterCancel = useRef(false);
  const withdrawOpener = useCallback((node: HTMLButtonElement | null) => {
    // 언마운트(node === null)와 평상시 마운트에는 아무것도 하지 않는다 — 취소로 돌아온
    // 경우에만 초점을 옮겨야, 화면에 처음 들어올 때 초점을 낚아채지 않는다.
    if (!node || !focusOpenerAfterCancel.current) return;
    focusOpenerAfterCancel.current = false;
    node.focus();
  }, []);

  const logoutMutation = useMutation({ mutationFn: logout });

  // 성공하면 isAuthenticated가 false가 되고 ProtectedRoute가 /login으로 보낸다
  // (로그아웃과 같은 경로) — 여기서 따로 navigate 하지 않는다.
  const withdrawMutation = useMutation({ mutationFn: withdraw });

  // 로그아웃과 탈퇴는 둘 다 지금 세션을 끝내는 요청이라, 하나가 나가 있는 동안 다른 하나를
  // 겹쳐 보낼 이유가 없다. (소셜 연동처럼 서로 무관한 동작끼리 묶는 것과는 다르다.)
  const busy = logoutMutation.isPending || withdrawMutation.isPending;

  // 동의 체크 전에 "탈퇴하기"가 왜 잠겼는지를 버튼에 aria-describedby로 묶기 위한 id.
  const agreeNoteId = useId();

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
          {/* disabled를 쓰지 않는다 — 누르는 순간 방금 누른 버튼이 초점을 잃어 <body>로
              떨어지고, 요청이 끝나 다시 활성화돼도 돌아오지 않는다. aria-busy는 초점을
              뺏지 않으면서 진행 중임을 알린다. 대신 aria-*는 표시일 뿐 클릭을 막지 않으므로
              막는 일은 핸들러가 직접 한다. */}
          <button
            aria-busy={logoutMutation.isPending}
            // 흐리게 보이고 눌러도 아무 일이 없는데 "사용 불가"라고 말하지 않으면
            // 보이는 모습과 읽히는 상태가 어긋난다.
            aria-disabled={busy || undefined}
            onClick={() => {
              if (busy) return;
              logoutMutation.mutate();
            }}
          >
            {logoutMutation.isPending ? "로그아웃 중…" : "로그아웃"}
          </button>
        </div>
        {logoutMutation.isError && <p className="error" role="alert">{errorMessage(logoutMutation.error)}</p>}
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
          <div
            className="settings-confirm"
            ref={confirmPanel}
            tabIndex={-1}
            role="group"
            aria-label="회원 탈퇴 확인"
          >
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
              {/* 여기서도 disabled를 쓰지 않지만, 쓰지 않는 이유가 두 조건에서 서로 다르다.
                  - !agreed: disabled면 버튼이 Tab 순회에서 통째로 빠져, 키보드·스크린리더
                    사용자는 확인 패널에 탈퇴 버튼이 있다는 사실 자체를 체크박스를 켜기
                    전까지 알 수 없다. 되돌릴 수 없는 동작인데 "무엇을 해야 진행되는지"가
                    버튼에 닿지 않는 것이다. aria-disabled로 두면 초점은 받되 "사용 불가"와
                    사유가 함께 읽힌다.
                  - busy: 누르는 순간 disabled가 걸리면 초점이 <body>로 떨어진다.
                    aria-busy는 초점을 뺏지 않는다. */}
              <button
                className="settings-withdraw"
                aria-busy={withdrawMutation.isPending}
                aria-disabled={!agreed || busy || undefined}
                // 사유 <p>는 !agreed일 때만 그려진다 — 없는 id를 가리키면 참조가 끊겨
                // 스크린리더가 아무것도 읽지 못한다.
                aria-describedby={!agreed ? agreeNoteId : undefined}
                onClick={() => {
                  if (!agreed || busy) return;
                  withdrawMutation.mutate();
                }}
              >
                {withdrawMutation.isPending ? "탈퇴 처리 중…" : "탈퇴하기"}
              </button>
              <button
                type="button"
                disabled={withdrawMutation.isPending}
                onClick={() => {
                  focusOpenerAfterCancel.current = true;
                  setConfirming(false);
                  setAgreed(false);
                }}
              >
                취소
              </button>
            </div>

            {/* 동의 체크박스의 라벨을 그대로 가리키지 않는다 — "위 안내를 확인했고, 탈퇴에
                동의합니다"는 이미 동의한 듯 들리는 서술문이라, 버튼 뒤에 이어 읽히면
                "그래서 무엇을 하라는 것인가"가 남지 않는다. 무엇을 켜야 하는지 지시로 적는다. */}
            {!agreed && (
              <p className="settings-desc" id={agreeNoteId}>
                탈퇴에 동의하는 위 체크박스를 먼저 켜야 진행할 수 있어요.
              </p>
            )}
          </div>
        ) : (
          <div className="card-actions">
            <button
              type="button"
              ref={withdrawOpener}
              disabled={busy}
              onClick={() => setConfirming(true)}
            >
              회원 탈퇴
            </button>
          </div>
        )}

        {withdrawMutation.isError && <p className="error" role="alert">{errorMessage(withdrawMutation.error)}</p>}
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

      {/* Safari + VoiceOver는 list-style: none이 걸린 <ul>에서 목록 시맨틱을 지운다. */}
      <ul className="settings-links" role="list">
        {PROVIDERS.map((provider) => (
          <SocialLinkRow
            key={provider}
            provider={provider}
            me={me}
            // 이름을 unlinking으로 못박는다. SettingsPage 최상단의 busy(로그아웃·탈퇴 진행)와
            // 이름만 같고 의미가 달라, "연동하기"까지 이 값으로 잠가 두었던 결함이
            // 오래 눈에 띄지 않았다.
            unlinking={unlinkMutation.isPending}
            onUnlink={() => unlinkMutation.mutate(provider)}
          />
        ))}
      </ul>

      {unlinkMutation.isError && <p className="error" role="alert">{errorMessage(unlinkMutation.error)}</p>}
    </section>
  );
}

function SocialLinkRow({
  provider,
  me,
  unlinking,
  onUnlink,
}: {
  provider: Provider;
  me: Me;
  /** 이 화면 어딘가에서 해제 요청이 나가 있는 상태. 제공자별이 아니라 섹션 전체가 하나다. */
  unlinking: boolean;
  onUnlink: () => void;
}) {
  const linked = me.linkedProviders.includes(provider);

  // 서버가 최종 판단하지만(LAST_LOGIN_METHOD), 누르면 반드시 실패하는 버튼을 열어두면
  // 사용자는 왜 안 되는지 모른 채 에러만 본다. 판정 기준은 백엔드 AuthService.unlink와 같다:
  // "해제한 뒤에도 들어올 문이 하나는 남는가". 비밀번호가 있거나, 다른 소셜 연동이 남으면 된다.
  const lastLoginMethod = linked && !me.hasPassword && me.linkedProviders.length === 1;

  // 해제 불가 사유를 버튼에 aria-describedby로 묶기 위한 id. 비밀번호 폼의 검증 메시지와
  // 같은 방식이다 — 초점이 버튼에 닿는 순간 이름 뒤에 이유가 이어서 읽힌다.
  const noteId = useId();

  // 둘 다 disabled를 쓰지 않지만, 쓰지 않는 이유가 서로 다르다.
  // - lastLoginMethod: disabled면 버튼이 Tab 순회에서 통째로 빠져 키보드·스크린리더
  //   사용자는 해제 버튼이 있다는 것도, 왜 못 쓰는지도 알 수 없다. aria-disabled로 두면
  //   초점은 받되 "사용 불가"와 사유가 함께 읽힌다.
  // - unlinking: 누르는 순간 disabled가 걸리면 방금 누른 버튼에서 초점이 <body>로 떨어지고
  //   요청이 끝나 다시 활성화돼도 돌아오지 않는다. aria-busy는 초점을 뺏지 않는다.
  // 다만 aria-*는 표시일 뿐 클릭을 막지 않는다 — 막는 일은 핸들러가 직접 해야 한다.
  const blocked = unlinking || lastLoginMethod;

  return (
    <li className="settings-link-row">
      {/* 제공자 이름과 상태를 각각 제 요소에 둔다 — 한 덩어리로 묶으면 화면에서도
          "카카오연동됨"으로 붙어 읽히고 스크린리더도 한 문장으로 흘려 읽는다. */}
      <span className="settings-link-name">{PROVIDER_LABELS[provider]}</span>
      <span className="settings-link-state">{linked ? "연동됨" : "연동 안 됨"}</span>

      {/* 버튼 이름에 제공자를 넣는다. 한 화면에 제공자 수만큼 놓이므로 "연동하기"만으로는
          스크린리더 사용자가 어느 쪽 버튼인지 구분할 수 없다. */}
      {linked ? (
        <button
          type="button"
          aria-busy={unlinking}
          // 진행 중에도 aria-disabled를 건다. 흐리게 보이고 눌러도 아무 일이 없는데
          // "사용 불가"라고 말하지 않으면, 보이는 모습과 읽히는 상태가 어긋난다.
          aria-disabled={blocked || undefined}
          // 사유 <p>는 lastLoginMethod일 때만 그려진다 — 없는 id를 가리키면 참조가 끊겨
          // 스크린리더가 아무것도 읽지 못한다.
          aria-describedby={lastLoginMethod ? noteId : undefined}
          onClick={() => {
            if (blocked) return;
            onUnlink();
          }}
        >
          {PROVIDER_LABELS[provider]} 연동 해제
        </button>
      ) : (
        // 로그인과 같은 인가 흐름을 타되 모드만 다르다 — 브라우저를 통째로 제공자로 보낸다.
        //
        // 진행 중 잠금(unlinking)을 걸지 않는다. 같은 제공자의 해제/연동은 linked에 따라
        // 둘 중 하나만 그려지므로 애초에 겹쳐 눌릴 수 없고, 그래서 이 잠금이 실제로 막고
        // 있던 것은 "다른 제공자의 연동"뿐이었다 — 카카오를 해제하는 동안 구글 연동하기가
        // 초점을 잃고 잠겼다. 두 제공자는 서로 독립이라 막을 이유가 없다.
        // 겹쳐도 안전하다: 연동하기는 브라우저를 통째로 제공자로 보내므로 이 화면이 통째로
        // 사라지고, 나가 있던 해제 요청은 서버에서 그대로 끝난다. 돌아오면 /me를 다시 받아
        // 최종 상태를 그린다. aria-disabled로 남겨 두는 것도 답이 아니다 — 실제로 막을
        // 이유가 없는 것을 "사용 불가"라고 읽어주는 거짓 안내가 된다.
        <button
          type="button"
          onClick={() => (window.location.href = linkAuthorizeUrl(provider))}
        >
          {PROVIDER_LABELS[provider]} 연동하기
        </button>
      )}

      {lastLoginMethod && (
        <p className="settings-desc settings-link-note" id={noteId}>
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
  // 검증 메시지는 role="alert"를 쓰지 않는다. 타이핑하는 동안 나타났다 사라지므로
  // 글자마다 낭독을 가로채 오히려 방해가 된다. 대신 aria-describedby로 필드에 묶어
  // 두면, 그 칸으로 이동했을 때 이름 뒤에 이유가 함께 읽힌다.
  const tooShortId = useId();
  const mismatchId = useId();
  const canSubmit = current.length > 0 && next.length >= PASSWORD_MIN_LENGTH && !mismatch;

  // "입력이 덜 찼다(!canSubmit)"와 "요청이 나가 있다(isPending)"는 성격이 다르다.
  // 앞은 사용자가 무엇을 더 해야 하는 조건이라 사유를 읽어줘야 하고, 뒤는 잠깐 기다리면
  // 풀리는 진행 상태다. 둘 다 disabled로 뭉뚱그리면 어느 쪽이든 초점만 잃는다.
  const submitBlocked = changeMutation.isPending || !canSubmit;

  // 잠긴 이유는 이미 각 칸 아래에 떠 있다 — 버튼 전용 문구를 새로 만들지 않고 그 메시지를
  // 버튼에도 이어 붙인다. 초점이 버튼에 닿는 순간 이름 뒤로 이유가 함께 읽힌다.
  // (아직 아무것도 안 적은 상태에는 띄울 메시지가 없다. 빈 칸은 required로 이미 드러난다.)
  const blockedReasonIds =
    [tooShort ? tooShortId : "", mismatch ? mismatchId : ""].filter(Boolean).join(" ") ||
    undefined;

  return (
    <section className="card settings-section">
      <strong>비밀번호 변경</strong>
      <p className="settings-desc">
        변경하면 다른 기기의 로그인이 모두 해제됩니다.
      </p>

      <form
        className="menu-form settings-password-form"
        // 제출 경로가 버튼 클릭만이 아니다 — 입력칸에서 Enter를 쳐도 여기로 온다.
        // 버튼에서 disabled를 뗀 이상 막는 자리는 클릭 핸들러가 아니라 여기다.
        onSubmit={(e) => {
          e.preventDefault();
          if (submitBlocked) return;
          changeMutation.mutate();
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
            aria-invalid={tooShort || undefined}
            aria-describedby={tooShort ? tooShortId : undefined}
          />
        </label>
        {tooShort && (
          <p className="error" id={tooShortId}>비밀번호는 {PASSWORD_MIN_LENGTH}자 이상이어야 합니다.</p>
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
            aria-invalid={mismatch || undefined}
            aria-describedby={mismatch ? mismatchId : undefined}
          />
        </label>
        {mismatch && <p className="error" id={mismatchId}>비밀번호가 서로 다릅니다.</p>}

        {changeMutation.isError && <p className="error" role="alert">{errorMessage(changeMutation.error)}</p>}
        {changeMutation.isSuccess && (
          <p className="settings-desc" role="status">비밀번호를 변경했습니다.</p>
        )}

        <div className="card-actions">
          <button
            type="submit"
            aria-busy={changeMutation.isPending}
            aria-disabled={submitBlocked || undefined}
            aria-describedby={blockedReasonIds}
          >
            {changeMutation.isPending ? "변경 중…" : "비밀번호 변경"}
          </button>
        </div>
      </form>
    </section>
  );
}
