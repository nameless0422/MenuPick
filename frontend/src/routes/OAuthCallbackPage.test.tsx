import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import OAuthCallbackPage from "./OAuthCallbackPage";
import { linkSocialAccount, loginWithOAuth } from "../api/auth";
import { apiErrorCode } from "../api/http";
import { useAuth } from "../auth/AuthContext";
import { linkAuthorizeUrl, loginAuthorizeUrl } from "../auth/oauthUrls";

// 콜백 화면이 하는 일은 "어디로 보낼지 고르는 것"이다. 실제 HTTP와 세션 복원은 여기서
// 보려는 대상이 아니라 전부 갈아 끼운다 — 진짜 AuthProvider는 마운트하자마자 토큰
// 재발급을 호출하고, 그게 이 화면의 분기와 얽히면 무엇을 검증했는지 알 수 없게 된다.
vi.mock("../api/auth", () => ({
  loginWithOAuth: vi.fn(),
  linkSocialAccount: vi.fn(),
}));
vi.mock("../api/http", () => ({
  apiErrorCode: vi.fn(),
  apiErrorMessage: vi.fn(() => "요청에 실패했습니다."),
}));
vi.mock("../auth/AuthContext", () => ({ useAuth: vi.fn() }));

const loginWithOAuthMock = vi.mocked(loginWithOAuth);
const linkSocialAccountMock = vi.mocked(linkSocialAccount);
const apiErrorCodeMock = vi.mocked(apiErrorCode);
const useAuthMock = vi.mocked(useAuth);

const loginFn = vi.fn();

/** 착지한 경로와 함께 넘어온 state를 눈에 보이게 찍어 준다. */
function Landing({ label }: { label: string }) {
  const { state } = useLocation();
  return <p>{label}:{JSON.stringify(state ?? null)}</p>;
}

function renderCallback(state: string | null, code: string | null = "auth_code") {
  const query = new URLSearchParams();
  if (state !== null) query.set("state", state);
  if (code !== null) query.set("code", code);

  return render(
    <MemoryRouter initialEntries={[`/oauth/kakao/callback?${query}`]}>
      <Routes>
        <Route path="/oauth/kakao/callback" element={<OAuthCallbackPage provider="kakao" />} />
        <Route path="/login" element={<Landing label="로그인 화면" />} />
        <Route path="/settings" element={<Landing label="설정 화면" />} />
        <Route path="/menus" element={<Landing label="메뉴 화면" />} />
      </Routes>
    </MemoryRouter>,
  );
}

/** 실제 발급 경로를 그대로 태워 sessionStorage를 채운다 — 모드 왕복까지 함께 검증된다. */
function startedState(url: string): string {
  return new URL(url, "http://localhost").searchParams.get("state")!;
}

beforeEach(() => {
  sessionStorage.clear();
  vi.clearAllMocks();
  useAuthMock.mockReturnValue({
    isAuthenticated: true,
    isLoading: false,
    login: loginFn,
    logout: vi.fn(),
    withdraw: vi.fn(),
    sessionExpired: false,
  });
});

describe("OAuthCallbackPage - 로그인 모드", () => {
  it("로그인에 성공하면 토큰을 세션에 넣고 기본 착지점으로 보낸다", async () => {
    const state = startedState(loginAuthorizeUrl("kakao"));
    loginWithOAuthMock.mockResolvedValue("access_token");

    renderCallback(state);

    expect(await screen.findByText(/메뉴 화면/)).toBeInTheDocument();
    expect(loginWithOAuthMock).toHaveBeenCalledWith("kakao", "auth_code");
    expect(loginFn).toHaveBeenCalledWith("access_token");
    expect(linkSocialAccountMock).not.toHaveBeenCalled();
  });

  // 소셜만으로는 가입되지 않는다. "로그인 실패"라고만 하면 사용자는 계정이 있는데 왜
  // 안 되는지 알 길이 없어 같은 버튼을 계속 다시 누른다.
  it("연동된 적 없는 소셜 계정이면 안내를 달고 로그인 화면으로 되돌려 보낸다", async () => {
    const state = startedState(loginAuthorizeUrl("kakao"));
    loginWithOAuthMock.mockRejectedValue(new Error("401"));
    apiErrorCodeMock.mockReturnValue("SOCIAL_ACCOUNT_NOT_LINKED");

    renderCallback(state);

    expect(await screen.findByText(/로그인 화면/)).toBeInTheDocument();
    expect(screen.getByText(/"socialNotLinked":"kakao"/)).toBeInTheDocument();
  });

  it("그 밖의 실패는 서버 메시지를 그대로 보여준다", async () => {
    const state = startedState(loginAuthorizeUrl("kakao"));
    loginWithOAuthMock.mockRejectedValue(new Error("boom"));
    apiErrorCodeMock.mockReturnValue("OAUTH_INVALID_CODE");

    renderCallback(state);

    expect(await screen.findByText(/요청에 실패했습니다./)).toBeInTheDocument();
  });
});

describe("OAuthCallbackPage - 연동 모드", () => {
  it("연동으로 시작했으면 로그인이 아니라 연동을 호출하고 설정 화면으로 돌아간다", async () => {
    const state = startedState(linkAuthorizeUrl("kakao"));
    linkSocialAccountMock.mockResolvedValue({ linkedProviders: ["kakao"], accessToken: "연동-후-토큰" });

    renderCallback(state);

    expect(await screen.findByText(/설정 화면/)).toBeInTheDocument();
    expect(linkSocialAccountMock).toHaveBeenCalledWith("kakao", "auth_code");
    // 연동은 소셜 로그인이 아니다 — 인가 코드를 로그인 경로로 보내면 안 된다.
    expect(loginWithOAuthMock).not.toHaveBeenCalled();
    // 다만 서버가 연동과 함께 모든 세션을 끊으므로, 함께 온 새 토큰은 적용해야 한다.
    // 적용하지 않으면 지금 토큰이 만료되는 순간 재발급이 막혀 조용히 로그아웃된다.
    expect(loginFn).toHaveBeenCalledWith("연동-후-토큰");
  });

  it("연동을 마쳤다는 표시를 설정 화면에 넘긴다", async () => {
    const state = startedState(linkAuthorizeUrl("kakao"));
    linkSocialAccountMock.mockResolvedValue({ linkedProviders: ["kakao"], accessToken: "연동-후-토큰" });

    renderCallback(state);

    expect(await screen.findByText(/"socialLinked":"kakao"/)).toBeInTheDocument();
  });

  // 다른 계정이 이미 쓰고 있는 소셜 계정(SOCIAL_ACCOUNT_TAKEN) 등은 서버가 거절한다.
  // 그 사유를 그대로 보여주고, 돌아갈 곳은 로그인 화면이 아니라 설정 화면이어야 한다 —
  // 이미 로그인한 사용자를 로그인 화면으로 보내면 그대로 튕겨 나온다.
  it("연동이 거절되면 사유를 보여주고 설정 화면으로 돌아갈 길을 준다", async () => {
    const state = startedState(linkAuthorizeUrl("kakao"));
    linkSocialAccountMock.mockRejectedValue(new Error("409"));

    renderCallback(state);

    expect(await screen.findByText(/요청에 실패했습니다./)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "돌아가기" })).toHaveAttribute("href", "/settings");
  });

  it("왕복 중에 세션이 끊겼으면 연동을 시도하지 않고 다시 로그인하라고 안내한다", async () => {
    const state = startedState(linkAuthorizeUrl("kakao"));
    useAuthMock.mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      login: loginFn,
      logout: vi.fn(),
      withdraw: vi.fn(),
      sessionExpired: false,
    });

    renderCallback(state);

    expect(await screen.findByText(/로그인이 풀렸습니다/)).toBeInTheDocument();
    expect(linkSocialAccountMock).not.toHaveBeenCalled();
    expect(screen.getByRole("link", { name: "돌아가기" })).toHaveAttribute("href", "/login");
  });
});

describe("OAuthCallbackPage - 요청 검증", () => {
  // 공격자가 자기 인가 코드를 피해자 브라우저의 콜백 URL로 밀어넣는 로그인 CSRF.
  it("이 브라우저가 시작하지 않은 요청이면 인가 코드를 서버로 보내지 않는다", async () => {
    loginAuthorizeUrl("kakao"); // 우리 값은 따로 있고

    renderCallback("남이_넣은_state");

    expect(await screen.findByText(/로그인 요청이 유효하지 않습니다/)).toBeInTheDocument();
    expect(loginWithOAuthMock).not.toHaveBeenCalled();
    expect(linkSocialAccountMock).not.toHaveBeenCalled();
  });

  it("인가 코드가 없으면 요청하지 않는다", async () => {
    const state = startedState(loginAuthorizeUrl("kakao"));

    renderCallback(state, null);

    expect(await screen.findByText(/인가 코드가 없습니다/)).toBeInTheDocument();
    expect(loginWithOAuthMock).not.toHaveBeenCalled();
  });

  // 부팅 시 토큰 재발급이 끝나기 전에 쏘면 연동 요청이 토큰 없이 나가 401을 한 번 맞는다.
  // 그러면 state는 이미 소비된 뒤라 재시도 경로가 인터셉터에만 의존하게 된다.
  it("세션 복원이 끝나기 전에는 아무것도 보내지 않는다", () => {
    const state = startedState(linkAuthorizeUrl("kakao"));
    useAuthMock.mockReturnValue({
      isAuthenticated: false,
      isLoading: true,
      login: loginFn,
      logout: vi.fn(),
      withdraw: vi.fn(),
      sessionExpired: false,
    });

    renderCallback(state);

    expect(linkSocialAccountMock).not.toHaveBeenCalled();
    expect(loginWithOAuthMock).not.toHaveBeenCalled();
    // state를 소비하지 않았으므로 복원이 끝난 뒤에도 검증이 성립한다
    expect(sessionStorage.getItem("oauth_request_kakao")).not.toBeNull();
  });
});

/**
 * 이 화면은 제공자에서 리다이렉트로 새로 뜬 문서다 — 스크린리더가 문서를 처음부터 다시
 * 읽기 시작하는 자리인데, 여태 {@code <h1>}도 없는 맨 {@code <p>} 한 줄이었다.
 *
 * <p>제목이 없으면 제목 탐색으로도 문서 개요로도 잡히는 것이 없어 "지금 어디에 도착했는지"를
 * 알 방법이 없다. 실패했을 때는 그 상태로 오류 문장과 "돌아가기" 링크만 덩그러니 남는다.
 */
describe("콜백 화면의 문서 제목", () => {
  it("처리 중 화면에도 <h1>이 있다", () => {
    const state = startedState(loginAuthorizeUrl("kakao"));
    // 응답을 붙잡아 둬야 "처리 중" 화면이 그 자리에 멈춘다.
    loginWithOAuthMock.mockReturnValue(new Promise<string>(() => {}));

    renderCallback(state);

    expect(screen.getByRole("heading", { level: 1, name: "소셜 로그인" })).toBeInTheDocument();
  });

  it("실패 화면의 제목이 로그인과 연동을 구분한다", async () => {
    // 연동하러 왔다는 사실은 state에 실려 있다 — 여기서 "로그인하지 못했습니다"라고 하면
    // 설정에서 연동을 누른 사람이 자기 계정이 잘못된 줄 안다.
    const state = startedState(linkAuthorizeUrl("kakao"));
    linkSocialAccountMock.mockRejectedValue(new Error("boom"));

    renderCallback(state);

    expect(
      await screen.findByRole("heading", { level: 1, name: "연동하지 못했습니다" }),
    ).toBeInTheDocument();
  });
});

/**
 * 동의 화면에서 "취소"를 누르면 제공자는 code 대신 error를 싣고 돌아온다
 * (카카오·구글 모두 OAuth 2.0 규격대로 `error=access_denied`, state는 그대로 echo).
 * 이 분기가 없으면 "인가 코드가 없습니다"라는 기술적 문구가 떠서, 스스로 취소한
 * 사용자가 무언가 고장 난 줄 알고 같은 버튼을 다시 누른다.
 */
describe("OAuthCallbackPage - 제공자가 error를 실어 보낸 경우", () => {
  function renderWithError(state: string | null, error: string) {
    const query = new URLSearchParams();
    if (state !== null) query.set("state", state);
    query.set("error", error);
    query.set("error_description", "User denied access");

    return render(
      <MemoryRouter initialEntries={[`/oauth/kakao/callback?${query}`]}>
        <Routes>
          <Route path="/oauth/kakao/callback" element={<OAuthCallbackPage provider="kakao" />} />
          <Route path="/login" element={<Landing label="로그인 화면" />} />
          <Route path="/settings" element={<Landing label="설정 화면" />} />
          <Route path="/menus" element={<Landing label="메뉴 화면" />} />
        </Routes>
      </MemoryRouter>,
    );
  }

  it("로그인 취소는 '취소했다'고 알리고 서버로는 아무것도 보내지 않는다", async () => {
    const state = startedState(loginAuthorizeUrl("kakao"));

    renderWithError(state, "access_denied");

    expect(await screen.findByRole("alert")).toHaveTextContent(/취소/);
    expect(screen.getByRole("alert")).not.toHaveTextContent(/인가 코드/);
    expect(loginWithOAuthMock).not.toHaveBeenCalled();
  });

  it("연동 취소는 설정 화면 얘기를 하고 돌아가기도 설정으로 간다", async () => {
    // 연동하러 온 사람에게 "로그인 화면에서 다시"라고 하면, 이미 로그인돼 있는 사용자가
    // 로그인 화면으로 갔다가 그대로 튕겨 나온다.
    const state = startedState(linkAuthorizeUrl("kakao"));

    renderWithError(state, "access_denied");

    expect(await screen.findByRole("alert")).toHaveTextContent(/연동을 취소/);
    expect(screen.getByRole("heading")).toHaveTextContent("연동하지 못했습니다");
    expect(screen.getByRole("link", { name: "돌아가기" })).toHaveAttribute("href", "/settings");
    expect(linkSocialAccountMock).not.toHaveBeenCalled();
  });

  it("취소가 아닌 오류는 코드를 남겨 되짚을 수 있게 한다", async () => {
    const state = startedState(loginAuthorizeUrl("kakao"));

    renderWithError(state, "invalid_scope");

    expect(await screen.findByRole("alert")).toHaveTextContent("invalid_scope");
  });

  it("제공자가 준 코드를 그대로 화면에 싣지 않는다", async () => {
    // error 값은 제공자(그리고 주소창)가 정한다 — 문자 집합과 길이를 좁혀서 넣는다.
    const state = startedState(loginAuthorizeUrl("kakao"));

    renderWithError(state, "<img src=x onerror=alert(1)>");

    const alert = await screen.findByRole("alert");
    // 마크업으로 읽힐 문자는 문구에 남지 않고, 요소도 들어오지 않는다.
    expect(alert.textContent).not.toMatch(/[<>"'`=/]/);
    expect(alert.querySelector("*")).toBeNull();
  });

  it("긴 코드는 잘라서 싣는다", async () => {
    const state = startedState(loginAuthorizeUrl("kakao"));

    renderWithError(state, "e".repeat(500));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent!.length).toBeLessThan(100);
  });

  it("state가 어긋나도 '요청이 유효하지 않다'가 아니라 취소로 안내한다", async () => {
    // 서버로 보낼 인가 코드 자체가 없는 경로다 — CSRF로 볼 것이 없는데
    // 취소한 사용자에게 보안 실패처럼 들리는 문구를 내밀 이유가 없다.
    startedState(loginAuthorizeUrl("kakao"));

    renderWithError("남의-state", "access_denied");

    expect(await screen.findByRole("alert")).toHaveTextContent(/취소/);
  });
});
