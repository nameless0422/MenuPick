import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { linkSocialAccount, loginWithOAuth, type Provider } from "../api/auth";
import { apiErrorCode, apiErrorMessage } from "../api/http";
import { useAuth } from "../auth/AuthContext";
import { consumeOAuthRequest } from "../auth/oauthUrls";
import { consumeReturnTo } from "../auth/returnTo";
import "./AuthPages.css";

const INVALID_REQUEST_MESSAGE =
  "로그인 요청이 유효하지 않습니다. 다시 시도해주세요.";

/** 이메일 로그인과 같은 기본 착지점 — 로그인 수단에 따라 도착지가 달라지지 않게 맞춘다. */
const DEFAULT_LANDING = "/menus";

/** 연동은 설정 화면에서 시작하므로 끝난 자리도 거기다. */
const SETTINGS = "/settings";

export default function OAuthCallbackPage({ provider }: { provider: Provider }) {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { login, isAuthenticated, isLoading } = useAuth();
  const [error, setError] = useState<string | null>(null);
  // 실패했을 때 돌아갈 곳. 로그인 실패면 로그인 화면, 연동 실패면 설정 화면이다 —
  // 연동하러 온 사용자를 로그인 화면으로 보내면 이미 로그인돼 있어 그대로 튕겨 나간다.
  const [retryPath, setRetryPath] = useState("/login");
  // StrictMode에서 effect가 두 번 실행되는 것을 막는다 — 인가 코드는 1회용이라
  // 두 번째 호출은 항상 실패한다. state 검증도 1회성(consume)이므로 같은 가드를 공유한다.
  const requested = useRef(false);

  useEffect(() => {
    // Access Token은 메모리에만 있어 리다이렉트 왕복으로 날아간다. AuthProvider가 부팅 때
    // 재발급을 한 번 돌리는데, 그게 끝나기 전에 연동 요청을 보내면 토큰 없이 나가 401을
    // 맞고 인터셉터 재시도에 기대게 된다. 여기서 기다리면 그 왕복이 아예 없다.
    if (isLoading) return;
    if (requested.current) return;
    requested.current = true;

    // state를 먼저 대조한다 — 우리가 시작하지 않은 로그인(로그인 CSRF)이면
    // 인가 코드를 서버로 보내지 않고 여기서 끊는다. 통과하면 시작할 때의 모드를 돌려준다.
    const mode = consumeOAuthRequest(provider, searchParams.get("state"));
    // 규칙(set-state-in-effect)은 "렌더에서 파생하라"고 하지만 여기서는 그럴 수 없다.
    // consumeOAuthRequest는 이름 그대로 1회성 소비라 렌더 중에 부르면 StrictMode의 이중
    // 렌더에서 두 번 소비돼 멀쩡한 로그인이 CSRF로 오인돼 끊긴다. 아래 인가 코드 검사도
    // 같은 이유로 못 올라간다 — state 대조를 통과한 뒤에야 볼 값이라 순서가 곧 의미다.
    // 마운트 시 한 번 돌리는 외부 작업의 실패 분기이므로 effect가 맞는 자리다.
    if (!mode) {
      // oxlint-disable-next-line react/set-state-in-effect
      setError(INVALID_REQUEST_MESSAGE);
      return;
    }
    if (mode === "link") setRetryPath(SETTINGS);

    const code = searchParams.get("code");
    if (!code) {
      setError("인가 코드가 없습니다.");
      return;
    }

    if (mode === "link") {
      // 연동 중에 세션이 끊겼다면(왕복이 길어 Refresh Token까지 만료) 붙일 계정이 없다.
      // 그대로 보내면 401만 받으므로 무엇을 해야 하는지 알려준다.
      if (!isAuthenticated) {
        setRetryPath("/login");
        setError("로그인이 풀렸습니다. 다시 로그인한 뒤 연동해주세요.");
        return;
      }
      linkSocialAccount(provider, code)
        // 연동 결과는 설정 화면이 안내 문구로 보여준다. 목록 자체는 그 화면이 /me로 다시 읽는다 —
        // 이 화면은 리다이렉트로 새로 뜬 문서라 넘겨줄 수 있는 캐시가 없다.
        .then(({ accessToken }) => {
          // 서버가 연동과 함께 그 계정의 모든 세션을 끊었다. 새 토큰을 적용하지 않으면 지금
          // 들고 있는 Access Token은 살아 있어도 그 뒤 재발급이 막혀, 30분쯤 지나 조용히
          // 로그아웃된다 — 사용자 입장에서는 연동한 것이 로그아웃의 원인이 된다.
          login(accessToken);
          navigate(SETTINGS, { replace: true, state: { socialLinked: provider } });
        })
        .catch((e) => setError(apiErrorMessage(e)));
      return;
    }

    loginWithOAuth(provider, code)
      .then((accessToken) => {
        login(accessToken);
        navigate(consumeReturnTo(DEFAULT_LANDING), { replace: true });
      })
      .catch((e) => {
        // 소셜만으로는 가입되지 않는다. 여기서 "로그인 실패"라고만 하면 사용자는 계정이
        // 있는데 왜 안 되는지 알 길이 없어 소셜 로그인을 계속 다시 누른다.
        // 안내는 가입 경로가 함께 있는 로그인 화면에서 하는 편이 낫다.
        if (apiErrorCode(e) === "SOCIAL_ACCOUNT_NOT_LINKED") {
          navigate("/login", { replace: true, state: { socialNotLinked: provider } });
          return;
        }
        setError(apiErrorMessage(e));
      });
  }, [searchParams, provider, login, navigate, isAuthenticated, isLoading]);

  // 이 화면은 제공자에서 리다이렉트로 새로 뜬 문서다 — 즉 스크린리더가 문서를 처음부터
  // 다시 읽기 시작하는 자리인데, 여태 <h1>도 스타일도 없는 맨 <p> 한 줄이었다.
  // 제목이 없으면 제목 탐색으로도 문서 개요로도 잡히는 것이 없어 "지금 어디에 도착했는지"를
  // 알 방법이 없고, 실패했을 때는 그 상태로 오류 문장과 "돌아가기" 링크만 덩그러니 남는다.
  // AuthPages.css를 함께 들여와 로그인·가입·메일 인증 화면과 같은 폭·여백을 쓴다 —
  // 이 화면만 빠져 있어 소셜 로그인 도중에 레이아웃이 통째로 무너졌다.
  // 이 화면은 로그인 콜백이기도 하고 연동 콜백이기도 하다 — 어느 쪽으로 왔는지는
  // 돌아갈 자리가 이미 알고 있다. 제목이 둘을 뭉뚱그리면 설정에서 연동하러 온 사람이
  // "로그인하지 못했습니다"를 듣고 자기 계정이 잘못된 줄 안다.
  const linking = retryPath === SETTINGS;

  if (error) {
    return (
      <div className="login-page">
        <h1>{linking ? "연동하지 못했습니다" : "로그인하지 못했습니다"}</h1>
        <p className="error" role="alert">{error}</p>
        <div className="auth-links">
          <a href={retryPath}>돌아가기</a>
        </div>
      </div>
    );
  }
  return (
    <div className="login-page">
      <h1>{linking ? "소셜 계정 연동" : "소셜 로그인"}</h1>
      <p>처리 중...</p>
    </div>
  );
}
