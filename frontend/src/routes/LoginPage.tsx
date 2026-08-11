import { useMutation } from "@tanstack/react-query";
import { kakaoAuthorizeUrl, googleAuthorizeUrl } from "../auth/oauthUrls";
import { requestDemoPick } from "../api/pick";
import { apiErrorMessage } from "../api/http";
import "./LoginPage.css";

export default function LoginPage() {
  // 가입 전에 픽을 한 번 보여주는 온보딩 퍼널 (docs/Planning.md 4.3).
  // 고정 샘플에서 뽑히며 저장되지 않는다.
  const demo = useMutation({ mutationFn: requestDemoPick });

  return (
    <div className="login-page">
      <h1>메뉴픽</h1>
      <p>오늘 뭐 먹지 고민을 대신 해드립니다.</p>

      <section className="card login-demo">
        <strong>먼저 구경해보기</strong>
        <p className="login-demo-desc">
          로그인 없이 뽑아볼 수 있어요. 샘플 메뉴로 시연하며, 결과는 저장되지 않습니다.
        </p>

        <button disabled={demo.isPending} onClick={() => demo.mutate()}>
          {demo.isPending ? "뽑는 중…" : demo.data ? "다시 뽑기" : "랜덤으로 하나 뽑아보기"}
        </button>

        {demo.data && (
          <div className="login-demo-result">
            <span className="login-demo-name">{demo.data.name}</span>
            <span className="login-demo-categories">{demo.data.categories.join(" · ")}</span>
            <p className="login-demo-cta">
              내 메뉴로 뽑고 기록까지 남기려면 로그인하세요.
            </p>
          </div>
        )}

        {demo.isError && <p className="error">{apiErrorMessage(demo.error)}</p>}
      </section>

      <div className="login-actions">
        <button onClick={() => (window.location.href = kakaoAuthorizeUrl())}>
          카카오로 로그인
        </button>
        <button onClick={() => (window.location.href = googleAuthorizeUrl())}>
          구글로 로그인
        </button>
      </div>
    </div>
  );
}
