import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import SettingsPage from "./SettingsPage";
import { fetchMe, unlinkSocialAccount, type Me } from "../api/auth";
import { useAuth } from "../auth/AuthContext";

// 실제 모듈의 상수(PROVIDERS·PROVIDER_LABELS·PASSWORD_MIN_LENGTH)는 화면이 그대로 쓰므로
// 살려 두고, 네트워크를 타는 함수만 갈아 끼운다.
vi.mock("../api/auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/auth")>()),
  fetchMe: vi.fn(),
  unlinkSocialAccount: vi.fn(),
  changePassword: vi.fn(),
}));
// 진짜 AuthProvider는 마운트하자마자 토큰 재발급을 호출한다. 이 화면에서 보려는 것은
// 연동 상태 표시와 해제 동작뿐이라 세션은 상태만 바꿔 끼운다.
vi.mock("../auth/AuthContext", () => ({ useAuth: vi.fn() }));

const fetchMeMock = vi.mocked(fetchMe);
const unlinkMock = vi.mocked(unlinkSocialAccount);
const useAuthMock = vi.mocked(useAuth);

const originalLocation = window.location;

function me(overrides: Partial<Me> = {}): Me {
  return {
    email: "user@example.com",
    nickname: "테스터",
    hasPassword: true,
    linkedProviders: [],
    ...overrides,
  };
}

/** 연동 직후 콜백 화면이 넘겨주는 state까지 재현하려면 initialEntries를 직접 줘야 한다. */
function renderSettings(state?: unknown) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[{ pathname: "/settings", state }]}>
        <SettingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  sessionStorage.clear();
  vi.clearAllMocks();
  useAuthMock.mockReturnValue({
    isAuthenticated: true,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
    withdraw: vi.fn(),
  });
  // 연동 시작은 브라우저를 통째로 제공자로 보낸다. jsdom은 실제 이동을 못 하므로
  // href만 받아 두는 자리로 바꿔 놓는다.
  Object.defineProperty(window, "location", {
    configurable: true,
    writable: true,
    value: { href: "http://localhost/settings" },
  });
});

afterEach(() => {
  Object.defineProperty(window, "location", {
    configurable: true,
    writable: true,
    value: originalLocation,
  });
});

describe("SettingsPage 소셜 계정 연동", () => {
  it("연동된 제공자와 아닌 제공자를 구분해 보여준다", async () => {
    fetchMeMock.mockResolvedValue(me({ linkedProviders: ["kakao"] }));

    renderSettings();

    expect(await screen.findByRole("button", { name: "카카오 연동 해제" })).toBeInTheDocument();
    // 연동 안 된 쪽에는 해제가 아니라 연동 버튼이 있어야 한다
    expect(screen.getByRole("button", { name: "구글 연동하기" })).toBeInTheDocument();
    expect(screen.getByText("연동됨")).toBeInTheDocument();
    expect(screen.getByText("연동 안 됨")).toBeInTheDocument();
  });

  it("연동하기를 누르면 link 모드로 백엔드 인가 엔드포인트에 보낸다", async () => {
    const user = userEvent.setup();
    fetchMeMock.mockResolvedValue(me());

    renderSettings();
    await user.click(await screen.findByRole("button", { name: "카카오 연동하기" }));

    expect(window.location.href).toContain("/api/v1/auth/kakao/authorize");
    // 콜백이 이 값을 보고 로그인이 아닌 연동으로 처리한다. login 모드로 나가면
    // 사용자는 연동을 눌렀는데 "연동된 계정이 없다"는 거절만 받게 된다.
    expect(sessionStorage.getItem("oauth_request_kakao")).toContain('"mode":"link"');
  });

  it("해제에 성공하면 다시 조회하지 않고 목록을 갱신한다", async () => {
    const user = userEvent.setup();
    fetchMeMock.mockResolvedValue(me({ linkedProviders: ["kakao"] }));
    unlinkMock.mockResolvedValue([]);

    renderSettings();
    await user.click(await screen.findByRole("button", { name: "카카오 연동 해제" }));

    expect(await screen.findByRole("button", { name: "카카오 연동하기" })).toBeInTheDocument();
    expect(unlinkMock).toHaveBeenCalledWith("kakao");
    // 서버가 갱신된 목록을 그대로 주므로 /me 재조회는 없어야 한다
    expect(fetchMeMock).toHaveBeenCalledTimes(1);
  });

  // 통과시키면 그 계정에는 영원히 들어갈 수 없고, 탈퇴조차 못 해 데이터만 남는다.
  // 서버도 LAST_LOGIN_METHOD로 막지만, 누르면 반드시 실패할 버튼을 열어두면
  // 사용자는 왜 안 되는지 모른 채 에러만 본다.
  it("마지막 로그인 수단이면 해제 버튼을 잠그고 이유를 알려준다", async () => {
    fetchMeMock.mockResolvedValue(me({ hasPassword: false, linkedProviders: ["kakao"] }));

    renderSettings();

    expect(await screen.findByRole("button", { name: "카카오 연동 해제" })).toBeDisabled();
    expect(screen.getByText(/마지막 로그인 수단이라 해제할 수 없어요/)).toBeInTheDocument();
  });

  it("비밀번호가 있으면 마지막 소셜 연동이어도 해제할 수 있다", async () => {
    fetchMeMock.mockResolvedValue(me({ hasPassword: true, linkedProviders: ["kakao"] }));

    renderSettings();

    expect(await screen.findByRole("button", { name: "카카오 연동 해제" })).toBeEnabled();
  });

  it("소셜 연동이 둘이면 비밀번호가 없어도 해제할 수 있다", async () => {
    fetchMeMock.mockResolvedValue(
      me({ hasPassword: false, linkedProviders: ["kakao", "google"] }),
    );

    renderSettings();

    expect(await screen.findByRole("button", { name: "카카오 연동 해제" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "구글 연동 해제" })).toBeEnabled();
  });

  it("해제가 거절되면 서버가 준 사유를 보여준다", async () => {
    const user = userEvent.setup();
    fetchMeMock.mockResolvedValue(me({ linkedProviders: ["kakao"] }));
    unlinkMock.mockRejectedValue(new Error("마지막 로그인 수단이라 해제할 수 없습니다."));

    renderSettings();
    await user.click(await screen.findByRole("button", { name: "카카오 연동 해제" }));

    await waitFor(() =>
      expect(
        screen.getByText("마지막 로그인 수단이라 해제할 수 없습니다."),
      ).toBeInTheDocument(),
    );
    // 실패했으니 화면은 여전히 "연동됨"이어야 한다
    expect(screen.getByRole("button", { name: "카카오 연동 해제" })).toBeInTheDocument();
  });

  it("연동을 마치고 돌아오면 그 사실을 알려준다", async () => {
    fetchMeMock.mockResolvedValue(me({ linkedProviders: ["kakao"] }));

    renderSettings({ socialLinked: "kakao" });

    expect(await screen.findByText("카카오 계정을 연동했습니다.")).toBeInTheDocument();
  });
});
