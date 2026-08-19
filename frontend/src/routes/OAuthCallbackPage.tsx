import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { linkSocialAccount, loginWithOAuth, type Provider } from "../api/auth";
import { apiErrorCode, apiErrorMessage } from "../api/http";
import { useAuth } from "../auth/AuthContext";
import { consumeOAuthRequest } from "../auth/oauthUrls";
import { consumeReturnTo } from "../auth/returnTo";

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
    if (!mode) {
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
        .then(() => navigate(SETTINGS, { replace: true, state: { socialLinked: provider } }))
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

  if (error) return <p>{error} <a href={retryPath}>돌아가기</a></p>;
  return <p>처리 중...</p>;
}
