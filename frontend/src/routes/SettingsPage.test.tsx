import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import SettingsPage from "./SettingsPage";
import { fetchMe, unlinkSocialAccount, type Me, type Provider } from "../api/auth";
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
  // 잠금은 disabled가 아니라 aria-disabled로 건다 — disabled면 버튼이 Tab 순회에서 통째로
  // 빠져 키보드·스크린리더 사용자는 해제 버튼의 존재조차 모르고, 아래 사유도 영영 닿지 않는다.
  it("마지막 로그인 수단이면 해제 버튼을 잠그고 이유를 알려준다", async () => {
    fetchMeMock.mockResolvedValue(me({ hasPassword: false, linkedProviders: ["kakao"] }));

    renderSettings();

    expect(await screen.findByRole("button", { name: "카카오 연동 해제" })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    expect(screen.getByText(/마지막 로그인 수단이라 해제할 수 없어요/)).toBeInTheDocument();
  });

  it("잠긴 해제 버튼도 초점을 받고, 초점이 닿으면 사유가 함께 읽힌다", async () => {
    fetchMeMock.mockResolvedValue(me({ hasPassword: false, linkedProviders: ["kakao"] }));

    renderSettings();
    const unlink = await screen.findByRole("button", { name: "카카오 연동 해제" });

    unlink.focus();
    expect(unlink).toHaveFocus();
    // 사유 <p>가 버튼과 이어져 있지 않으면 초점이 닿아도 "왜 못 쓰는지"는 낭독되지 않는다.
    expect(unlink).toHaveAccessibleDescription(/마지막 로그인 수단이라 해제할 수 없어요/);
  });

  // aria-disabled는 표시일 뿐 클릭을 막지 않는다. 핸들러가 조기 반환하지 않으면 버튼이
  // 실제로 눌려 LAST_LOGIN_METHOD 에러만 돌아온다 — 화면이 막아주던 것이 사라진다.
  it("잠긴 해제 버튼을 눌러도 해제 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    fetchMeMock.mockResolvedValue(me({ hasPassword: false, linkedProviders: ["kakao"] }));

    renderSettings();
    await user.click(await screen.findByRole("button", { name: "카카오 연동 해제" }));

    expect(unlinkMock).not.toHaveBeenCalled();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  // 진행 중에 disabled를 걸면 방금 누른 버튼에서 초점이 <body>로 떨어지고, 요청이 끝나
  // 다시 활성화돼도 돌아오지 않는다. aria-busy는 초점을 뺏지 않으면서 진행 중임을 알린다.
  it("해제 요청 중에도 버튼이 초점을 지키고 진행 중임을 알린다", async () => {
    const user = userEvent.setup();
    fetchMeMock.mockResolvedValue(me({ hasPassword: true, linkedProviders: ["kakao"] }));
    // 응답을 붙잡아 두어야 "요청 중" 상태를 멈춰 세워 놓고 볼 수 있다.
    unlinkMock.mockReturnValue(new Promise<Provider[]>(() => {}));

    renderSettings();
    const unlink = await screen.findByRole("button", { name: "카카오 연동 해제" });
    await user.click(unlink);

    await waitFor(() => expect(unlink).toHaveAttribute("aria-busy", "true"));
    expect(unlink).toHaveFocus();

    // 초점이 남아 있으니 연타가 가능해졌다 — 조기 반환이 없으면 같은 요청이 겹쳐 나간다.
    await user.click(unlink);
    expect(unlinkMock).toHaveBeenCalledTimes(1);
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

/**
 * 회원 탈퇴 확인 패널은 "회원 탈퇴" 버튼을 통째로 대체한다 — 방금 누른 버튼이 사라져
 * 초점이 {@code <body>}로 떨어지고, <b>되돌릴 수 없는 동작의 확인 패널이 열린 사실 자체가
 * 전달되지 않는다.</b> 취소도 마찬가지로 패널이 사라지며 초점을 잃었다.
 *
 * <p>같은 처리가 MenusPage·RestaurantsPage에는 이미 있었고 이 화면만 빠져 있었다.
 */
describe("회원 탈퇴 확인 패널의 초점", () => {
  beforeEach(() => {
    fetchMeMock.mockResolvedValue(me());
  });

  it("패널이 열리면 초점이 패널로 간다", async () => {
    const user = userEvent.setup();
    renderSettings();

    await user.click(await screen.findByRole("button", { name: "회원 탈퇴" }));

    const panel = await screen.findByRole("group", { name: "회원 탈퇴 확인" });
    await waitFor(() => expect(panel).toHaveFocus());
  });

  it("취소하면 초점이 '회원 탈퇴' 버튼으로 돌아온다", async () => {
    const user = userEvent.setup();
    renderSettings();

    await user.click(await screen.findByRole("button", { name: "회원 탈퇴" }));
    await user.click(await screen.findByRole("button", { name: "취소" }));

    // 취소하면 버튼이 새로 마운트된다 — 닫기 전에 잡아 둔 참조로는 갈 수 없다.
    await waitFor(() => expect(screen.getByRole("button", { name: "회원 탈퇴" })).toHaveFocus());
  });
});
