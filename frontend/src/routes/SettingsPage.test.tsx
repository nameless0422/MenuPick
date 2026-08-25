import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import SettingsPage from "./SettingsPage";
import {
  changePassword,
  fetchMe,
  unlinkSocialAccount,
  type Me,
  type Provider,
} from "../api/auth";
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
const changePasswordMock = vi.mocked(changePassword);
const useAuthMock = vi.mocked(useAuth);

// 로그아웃·탈퇴는 세션 훅이 쥐고 있다. 매번 새 vi.fn()을 끼우면 "요청이 나갔는가"를
// 테스트에서 붙잡을 수 없어, 한 번 만들어 두고 beforeEach에서 구현만 되돌린다.
const logoutFn = vi.fn<() => Promise<void>>();
const withdrawFn = vi.fn<() => Promise<void>>();

/** 응답을 붙잡아 두어야 "요청 중" 상태를 멈춰 세워 놓고 볼 수 있다. */
function pendingForever<T>() {
  return new Promise<T>(() => {});
}

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
  // clearAllMocks는 호출 기록만 지운다 — 앞선 테스트가 끼워 둔 구현이 남지 않도록 되돌린다.
  logoutFn.mockImplementation(() => Promise.resolve());
  withdrawFn.mockImplementation(() => Promise.resolve());
  useAuthMock.mockReturnValue({
    isAuthenticated: true,
    isLoading: false,
    sessionExpired: false,
    login: vi.fn(),
    logout: logoutFn,
    withdraw: withdrawFn,
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
    unlinkMock.mockReturnValue(pendingForever<Provider[]>());

    renderSettings();
    const unlink = await screen.findByRole("button", { name: "카카오 연동 해제" });
    await user.click(unlink);

    await waitFor(() => expect(unlink).toHaveAttribute("aria-busy", "true"));
    expect(unlink).toHaveFocus();

    // 초점이 남아 있으니 연타가 가능해졌다 — 조기 반환이 없으면 같은 요청이 겹쳐 나간다.
    await user.click(unlink);
    expect(unlinkMock).toHaveBeenCalledTimes(1);
  });

  // 해제 진행 상태로 "연동하기"까지 잠가 두면, 서로 아무 관계도 없는 두 제공자가 묶인다.
  // 카카오를 해제하는 동안 구글 연동하기가 초점을 잃고 잠기던 자리다. 같은 제공자의
  // 해제/연동은 애초에 둘 중 하나만 그려지므로, 이 잠금이 막던 것은 다른 제공자뿐이었다.
  it("다른 제공자를 해제하는 중에도 연동하기 버튼은 잠기지 않는다", async () => {
    const user = userEvent.setup();
    fetchMeMock.mockResolvedValue(me({ hasPassword: true, linkedProviders: ["kakao"] }));
    unlinkMock.mockReturnValue(pendingForever<Provider[]>());

    renderSettings();
    const unlink = await screen.findByRole("button", { name: "카카오 연동 해제" });
    await user.click(unlink);
    await waitFor(() => expect(unlink).toHaveAttribute("aria-busy", "true"));

    const link = screen.getByRole("button", { name: "구글 연동하기" });
    expect(link).toBeEnabled();
    // aria-disabled로 옮겨 잠그는 것도 답이 아니다 — 실제로 막을 이유가 없는데
    // "사용 불가"라고 읽어주면 거짓 안내가 된다.
    expect(link).not.toHaveAttribute("aria-disabled");

    // 초점을 받고, 눌러서 실제로 연동 흐름까지 시작할 수 있어야 한다.
    link.focus();
    expect(link).toHaveFocus();
    await user.click(link);
    expect(window.location.href).toContain("/api/v1/auth/google/authorize");
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

/**
 * 로그아웃·탈퇴하기·비밀번호 변경은 모두 {@code disabled={pending}} 형태였다. 누르는 순간
 * 방금 누른 버튼이 초점을 잃어 {@code <body>}로 떨어지고, 요청이 끝나 다시 활성화돼도
 * 돌아오지 않는다 — 키보드 사용자는 문서 처음부터 Tab을 다시 눌러야 한다.
 *
 * <p>aria-busy·aria-disabled는 초점을 뺏지 않는 대신 클릭도 막지 못한다. 조기 반환을
 * 빠뜨리면 잠긴 것처럼 보이는 버튼이 실제로 눌려 서버 요청이 나간다 — 아래 테스트가
 * 각각 그 자리를 지킨다.
 */
describe("로그아웃 버튼", () => {
  beforeEach(() => {
    fetchMeMock.mockResolvedValue(me());
  });

  it("요청 중에도 초점을 지키고 진행 중임을 알린다", async () => {
    const user = userEvent.setup();
    logoutFn.mockReturnValue(pendingForever<void>());

    renderSettings();
    const button = await screen.findByRole("button", { name: "로그아웃" });
    await user.click(button);

    const busyButton = await screen.findByRole("button", { name: "로그아웃 중…" });
    expect(busyButton).toHaveAttribute("aria-busy", "true");
    expect(busyButton).toHaveFocus();
  });

  it("요청 중에 다시 눌러도 같은 요청이 겹쳐 나가지 않는다", async () => {
    const user = userEvent.setup();
    logoutFn.mockReturnValue(pendingForever<void>());

    renderSettings();
    await user.click(await screen.findByRole("button", { name: "로그아웃" }));

    // 초점이 남아 있으니 연타가 가능해졌다 — 조기 반환이 없으면 그대로 두 번 나간다.
    const busyButton = await screen.findByRole("button", { name: "로그아웃 중…" });
    await user.click(busyButton);
    expect(logoutFn).toHaveBeenCalledTimes(1);
  });
});

describe("회원 탈퇴 확인 패널의 '탈퇴하기'", () => {
  beforeEach(() => {
    fetchMeMock.mockResolvedValue(me());
  });

  /** 확인 패널을 열고, 필요하면 동의까지 체크해 둔다. */
  async function openConfirm(user: ReturnType<typeof userEvent.setup>, agree: boolean) {
    await user.click(await screen.findByRole("button", { name: "회원 탈퇴" }));
    if (agree) await user.click(await screen.findByRole("checkbox"));
    return screen.getByRole("button", { name: "탈퇴하기" });
  }

  // disabled면 버튼이 Tab 순회에서 통째로 빠져, 키보드·스크린리더 사용자는 확인 패널에
  // 탈퇴 버튼이 있다는 사실 자체를 체크박스를 켜기 전까지 알 수 없다. 되돌릴 수 없는
  // 동작인데 "무엇을 해야 진행되는지"가 버튼에 닿지 않는다.
  it("동의 전에도 초점을 받고, 초점이 닿으면 무엇을 해야 하는지 함께 읽힌다", async () => {
    const user = userEvent.setup();
    renderSettings();

    const withdrawButton = await openConfirm(user, false);
    expect(withdrawButton).toHaveAttribute("aria-disabled", "true");

    withdrawButton.focus();
    expect(withdrawButton).toHaveFocus();
    // 사유가 버튼과 이어져 있지 않으면 초점이 닿아도 "왜 못 쓰는지"는 낭독되지 않는다.
    expect(withdrawButton).toHaveAccessibleDescription(/체크박스를 먼저 켜야/);
  });

  // aria-disabled는 표시일 뿐 클릭을 막지 않는다. 핸들러가 조기 반환하지 않으면 동의도
  // 하지 않은 채 되돌릴 수 없는 탈퇴 요청이 그대로 나간다.
  it("동의 전에 눌러도 탈퇴 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderSettings();

    await user.click(await openConfirm(user, false));

    expect(withdrawFn).not.toHaveBeenCalled();
  });

  it("동의를 체크하면 잠금이 풀리고 눌러서 탈퇴할 수 있다", async () => {
    const user = userEvent.setup();
    renderSettings();

    const withdrawButton = await openConfirm(user, true);
    expect(withdrawButton).not.toHaveAttribute("aria-disabled");
    // 사유가 사라진 뒤에도 참조가 남아 있으면 스크린리더가 빈 설명을 읽는다.
    expect(withdrawButton).not.toHaveAttribute("aria-describedby");

    await user.click(withdrawButton);
    expect(withdrawFn).toHaveBeenCalledTimes(1);
  });

  it("탈퇴 요청 중에도 초점을 지키고 연타되지 않는다", async () => {
    const user = userEvent.setup();
    withdrawFn.mockReturnValue(pendingForever<void>());
    renderSettings();

    await user.click(await openConfirm(user, true));

    const busyButton = await screen.findByRole("button", { name: "탈퇴 처리 중…" });
    expect(busyButton).toHaveAttribute("aria-busy", "true");
    expect(busyButton).toHaveFocus();

    await user.click(busyButton);
    expect(withdrawFn).toHaveBeenCalledTimes(1);
  });
});

describe("비밀번호 변경 버튼", () => {
  beforeEach(() => {
    fetchMeMock.mockResolvedValue(me({ hasPassword: true }));
  });

  /** 세 칸을 모두 채운다. required가 걸려 있어 하나라도 비면 제출 자체가 일어나지 않는다. */
  async function fillForm(
    user: ReturnType<typeof userEvent.setup>,
    next: string,
    nextCheck: string,
  ) {
    await user.type(await screen.findByLabelText("현재 비밀번호"), "current-pw");
    await user.type(screen.getByLabelText("새 비밀번호"), next);
    await user.type(screen.getByLabelText("새 비밀번호 확인"), nextCheck);
    return screen.getByRole("button", { name: "비밀번호 변경" });
  }

  // "입력이 덜 찼다"와 "요청이 나가 있다"는 성격이 다르다. 앞은 사용자가 무엇을 더 해야
  // 하는 조건이라 사유가 버튼까지 닿아야 하는데, disabled면 초점조차 받지 못한다.
  it("입력이 어긋나면 잠기되 초점은 받고, 이유가 함께 읽힌다", async () => {
    const user = userEvent.setup();
    renderSettings();

    const submit = await fillForm(user, "new-password", "new-passwerd");
    expect(submit).toHaveAttribute("aria-disabled", "true");

    submit.focus();
    expect(submit).toHaveFocus();
    expect(submit).toHaveAccessibleDescription(/비밀번호가 서로 다릅니다/);
  });

  // 제출 경로가 버튼 클릭만이 아니다 — 조기 반환이 form onSubmit에 없으면 눌리는 즉시
  // 서버로 나간다.
  it("잠긴 채로 눌러도 변경 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderSettings();

    await user.click(await fillForm(user, "new-password", "new-passwerd"));

    expect(changePasswordMock).not.toHaveBeenCalled();
  });

  // 입력칸에서 Enter를 쳐도 같은 form이 제출된다. 버튼만 막아 두면 이 경로로 새어 나간다.
  it("잠긴 채로 입력칸에서 Enter를 쳐도 변경 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderSettings();

    await fillForm(user, "new-password", "new-passwerd");
    await user.type(screen.getByLabelText("새 비밀번호 확인"), "{Enter}");

    expect(changePasswordMock).not.toHaveBeenCalled();
  });

  it("요청 중에도 초점을 지키고 연타되지 않는다", async () => {
    const user = userEvent.setup();
    changePasswordMock.mockReturnValue(pendingForever<string>());
    renderSettings();

    await user.click(await fillForm(user, "new-password", "new-password"));

    const busyButton = await screen.findByRole("button", { name: "변경 중…" });
    expect(busyButton).toHaveAttribute("aria-busy", "true");
    expect(busyButton).toHaveFocus();

    await user.click(busyButton);
    expect(changePasswordMock).toHaveBeenCalledTimes(1);
  });
});

/**
 * 섹션 제목 4개가 {@code <strong>}이었다 (SettingsPage.css가 색만 --text-h로 올려
 * "제목처럼 보이게만" 해 뒀다).
 *
 * <p>이 화면은 서로 독립적인 작업 네 개가 세로로 늘어선 구조인데, 제목 트리에는
 * {@code <h1>설정} 하나뿐이라 스크린리더의 제목 탐색(H 키)으로는 맨 위에서 한 발짝도
 * 움직일 수 없었다. {@code <section>}에도 접근 가능한 이름이 없어 랜드마크 목록에조차
 * 잡히지 않았으므로, 탈퇴 섹션까지 가려면 앞의 모든 버튼과 입력칸을 Tab으로 지나가는 것이
 * 유일한 길이었다. 눈으로 보이는 계층과 프로그램적 계층이 어긋나 있던 전형적인 예다.
 */
describe("설정 화면의 섹션 구조", () => {
  it("섹션 제목 4개가 <h2>로 제목 트리에 올라온다", async () => {
    fetchMeMock.mockResolvedValue(me({ hasPassword: true }));

    renderSettings();

    // 비밀번호 섹션은 /me가 도착해야(hasPassword) 그려진다.
    expect(
      await screen.findByRole("heading", { level: 2, name: "비밀번호 변경" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "계정" })).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 2, name: "소셜 계정 연동" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "회원 탈퇴" })).toBeInTheDocument();
  });

  it("각 섹션이 그 제목을 이름으로 가진 랜드마크가 된다", async () => {
    fetchMeMock.mockResolvedValue(me({ hasPassword: true }));

    renderSettings();

    // 이름이 없는 <section>은 랜드마크로 노출되지 않아 role="region"으로 잡히지도 않는다 —
    // 이 조회가 통과한다는 것은 곧 aria-labelledby가 제목에 실제로 닿아 있다는 뜻이다.
    expect(await screen.findByRole("region", { name: "비밀번호 변경" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "계정" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "소셜 계정 연동" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "회원 탈퇴" })).toBeInTheDocument();
  });
});
