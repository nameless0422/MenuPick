import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import LoginPage from "./LoginPage";
import { login, resendVerification } from "../api/auth";
import { useAuth } from "../auth/AuthContext";

vi.mock("../api/pick", () => ({ requestDemoPick: vi.fn() }));
vi.mock("../api/auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/auth")>()),
  login: vi.fn(),
  resendVerification: vi.fn(),
}));
vi.mock("../auth/AuthContext", () => ({ useAuth: vi.fn() }));

const originalLocation = window.location;

function renderLogin(state?: unknown) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[{ pathname: "/login", state }]}>
        <LoginPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  sessionStorage.clear();
  vi.mocked(useAuth).mockReturnValue({
    isAuthenticated: false,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
    withdraw: vi.fn(),
    sessionExpired: false,
  });
  Object.defineProperty(window, "location", {
    configurable: true,
    writable: true,
    value: { href: "http://localhost/login" },
  });
});

afterEach(() => {
  Object.defineProperty(window, "location", {
    configurable: true,
    writable: true,
    value: originalLocation,
  });
});

describe("LoginPage 세션 만료 안내", () => {
  // 만료는 아무 조작 없이 일어난다. 안내가 없으면 사용자는 화면이 왜 바뀌었는지 모른 채
  // 자기가 뭘 잘못 눌렀다고 생각하고, 하던 일로 돌아갈 수 있다는 사실도 알 수 없다.
  it("세션이 끊겨 밀려온 경우 왜 로그아웃됐는지 알려준다", () => {
    renderLogin({ sessionExpired: true });

    expect(
      screen.getByText(/자동으로 로그아웃됐어요/),
    ).toBeInTheDocument();
  });

  // 이 안내는 마운트 시점에 이미 내용을 갖고 나타나므로 라이브 리전으로는 통지되지 않는다.
  // 초점을 옮기지 않으면 화면에는 떠 있는데 스크린리더 사용자에게는 전달되지 않아,
  // "조용히 로그아웃됐다"는 원래 문제가 그대로 남는다.
  it("안내로 초점을 옮겨 스크린리더에 실제로 읽히게 한다", () => {
    renderLogin({ sessionExpired: true });

    expect(document.activeElement).toBe(screen.getByText(/자동으로 로그아웃됐어요/));
  });

  it("그냥 들어온 로그인 화면에는 만료 안내를 띄우지 않는다", () => {
    renderLogin();

    expect(screen.queryByText(/자동으로 로그아웃됐어요/)).not.toBeInTheDocument();
    // 초점도 건드리지 않는다 — 주소창에서 막 들어온 사용자의 초점을 빼앗을 이유가 없다.
    expect(document.activeElement).toBe(document.body);
  });
});

describe("LoginPage 소셜 로그인 안내", () => {
  // 소셜은 가입 경로가 아니다. 이 안내가 없으면 거절당한 사용자는 계정이 있는데도
  // 왜 안 되는지 모른 채 같은 버튼만 다시 누른다.
  it("연동 안 된 소셜로 로그인을 시도해 되돌아오면 무엇을 해야 하는지 알려준다", () => {
    renderLogin({ socialNotLinked: "kakao" });

    expect(screen.getByRole("status")).toHaveTextContent(
      /카카오 계정이 아직 연동돼 있지 않아요/,
    );
    // 안내만 하고 끝내면 갈 곳이 없다 — 가입으로 가는 길이 같은 문장 안에 있어야 한다.
    expect(screen.getByRole("link", { name: "이메일로 가입" })).toHaveAttribute("href", "/signup");
  });

  it("그냥 들어온 로그인 화면에는 그 안내를 띄우지 않는다", () => {
    renderLogin();

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("소셜 버튼은 login 모드로 인가 요청을 시작한다", async () => {
    const user = userEvent.setup();
    renderLogin();

    await user.click(screen.getByRole("button", { name: "카카오로 로그인" }));

    expect(window.location.href).toContain("/api/v1/auth/kakao/authorize");
    expect(sessionStorage.getItem("oauth_request_kakao")).toContain('"mode":"login"');
  });
});

/**
 * 로그인은 이 앱의 유일한 관문이다. 실패를 알리지 않으면 스크린리더 사용자는
 * 무슨 일이 일어났는지 알 수 없다 — 제출 버튼이 isPending 동안 disabled가 되면서
 * 초점까지 {@code <body>}로 되돌아가, 화면 맨 위에 선 채 아무 소리도 듣지 못한다.
 *
 * 같은 리포의 HistoryPage·MenusPage·PickPage는 이미 .error에 role="alert"를 붙여
 * 두었는데 인증 화면만 빠져 있었다.
 */
describe("LoginPage 실패 통지", () => {
  it("로그인 실패를 role=alert로 알린다", async () => {
    const user = userEvent.setup();
    vi.mocked(login).mockRejectedValue(new Error("이메일 또는 비밀번호가 올바르지 않습니다."));
    renderLogin();

    await user.type(screen.getByLabelText("이메일"), "a@b.com");
    await user.type(screen.getByLabelText("비밀번호"), "wrongpassword");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/올바르지 않습니다/);
  });
});

/**
 * 재발송 버튼이 자기 자신을 성공 문구로 갈아치우고 있었다.
 *
 * <p>포커스 소실과 문구 미고지가 동시에 일어난다. 여기는 <b>이미 로그인에 실패해 막힌
 * 사용자의 마지막 탈출구</b>인데, 눌러도 결과를 알 수 없으니 그대로 무한 재시도가 된다.
 */
describe("LoginPage 인증 메일 재발송", () => {
  async function failWithUnverified() {
    const user = userEvent.setup();
    vi.mocked(login).mockRejectedValue({
      isAxiosError: true,
      response: { data: { errorCode: "EMAIL_NOT_VERIFIED", message: "이메일 인증이 필요합니다." } },
    });
    renderLogin();

    await user.type(screen.getByLabelText("이메일"), "a@b.com");
    await user.type(screen.getByLabelText("비밀번호"), "password123");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    return user;
  }

  it("재발송에 성공해도 버튼이 사라지지 않는다 — 초점이 그대로 남는다", async () => {
    vi.mocked(resendVerification).mockResolvedValue(undefined as never);
    const user = await failWithUnverified();

    const resend = await screen.findByRole("button", { name: "인증 메일 다시 보내기" });
    await user.click(resend);

    // 버튼이 문구로 갈리면 방금 누른 요소가 사라져 초점이 <body>로 떨어진다.
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "인증 메일 다시 보내기" })).toBeInTheDocument(),
    );
    expect(screen.getByRole("button", { name: "인증 메일 다시 보내기" })).toHaveFocus();
  });

  it("재발송 결과를 통지한다", async () => {
    vi.mocked(resendVerification).mockResolvedValue(undefined as never);
    const user = await failWithUnverified();

    await user.click(await screen.findByRole("button", { name: "인증 메일 다시 보내기" }));

    await waitFor(() =>
      expect(
        screen.getAllByRole("status").some((r) => r.textContent?.includes("인증 메일을 다시 보냈어요")),
      ).toBe(true),
    );
  });
});

/** 응답이 오지 않는 요청 — "진행 중" 상태를 붙잡아 두기 위한 것이다. */
function pendingForever<T>() {
  return new Promise<T>(() => {});
}

/**
 * 제출 버튼은 {@code disabled={isPending || !email.trim() || !password}}였다.
 *
 * <p>미입력이면 처음부터 disabled라 Tab 순회에서 통째로 빠진다 — 스크린리더 사용자는
 * 로그인 버튼이 있다는 것도, 왜 로그인이 안 되는지도 알 수 없다. 게다가 입력의 required는
 * 버튼이 disabled인 한 <b>브라우저 기본 검증조차 트리거하지 못해</b>, "이메일을 채우라"는
 * 말이 영영 나오지 않았다. 막혀 있는데 이유를 말해주는 장치가 하나도 없는 상태다.
 *
 * <p>미입력으로는 더 이상 잠그지 않는다. 눌렀을 때 무엇이 비었는지 role="alert"로 알리고
 * 그 칸으로 초점을 옮긴다 — 원래 required가 하려던 일을 이쪽에서 직접 한다.
 */
describe("LoginPage 제출 버튼", () => {
  // 이 파일의 다른 describe들은 호출 횟수를 보지 않아 mock을 비우지 않고 지나간다.
  // 여기서는 "요청이 나갔는가"가 판정 기준이라 앞 테스트의 호출이 섞이면 안 된다.
  beforeEach(() => {
    vi.mocked(login).mockClear();
  });

  it("미입력이어도 버튼이 초점을 받는다", () => {
    renderLogin();

    const submit = screen.getByRole("button", { name: "로그인" });
    expect(submit).not.toBeDisabled();
    expect(submit).not.toHaveAttribute("aria-disabled");

    submit.focus();
    expect(submit).toHaveFocus();
  });

  it("빈 채로 누르면 무엇을 채워야 하는지 알리고 그 칸으로 초점을 옮긴다", async () => {
    const user = userEvent.setup();
    renderLogin();

    await user.click(screen.getByRole("button", { name: "로그인" }));

    // 비어 있는 칸을 모두 알린다 — 하나씩 알리면 고칠 때마다 다시 눌러 보게 된다.
    expect(screen.getAllByRole("alert").map((el) => el.textContent)).toEqual([
      "이메일을 입력해주세요.",
      "비밀번호를 입력해주세요.",
    ]);
    // 초점은 위에서 아래로 — 처음 만나는 빈 칸이 먼저 채워야 할 칸이다.
    expect(screen.getByLabelText("이메일")).toHaveFocus();
    // 초점만 옮기고 이유를 칸에 묶지 않으면, 나중에 그 칸으로 되돌아왔을 때 다시 들을 길이 없다.
    expect(screen.getByLabelText("이메일")).toHaveAccessibleDescription("이메일을 입력해주세요.");
    expect(login).not.toHaveBeenCalled();
  });

  it("이메일만 채우고 누르면 비밀번호 칸으로 초점이 간다", async () => {
    const user = userEvent.setup();
    renderLogin();

    await user.type(screen.getByLabelText("이메일"), "a@b.com");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(screen.getByRole("alert")).toHaveTextContent("비밀번호를 입력해주세요.");
    expect(screen.getByLabelText("비밀번호")).toHaveFocus();
    expect(login).not.toHaveBeenCalled();
  });

  // 제출 경로가 버튼 클릭만이 아니다 — 입력칸에서 Enter를 쳐도 같은 form이 제출된다.
  // 검사를 버튼 쪽에만 두면 이 경로로 그대로 새어 나간다.
  it("입력칸에서 Enter를 쳐도 빈 칸이 있으면 로그인 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderLogin();

    await user.type(screen.getByLabelText("이메일"), "a@b.com{Enter}");

    expect(login).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("비밀번호를 입력해주세요.");
    expect(screen.getByLabelText("비밀번호")).toHaveFocus();
  });

  // 채우기 전부터 빨간 문구가 떠 있으면 안내가 아니라 방해다.
  it("누르기 전에는 빈 칸을 오류로 부르지 않는다", async () => {
    const user = userEvent.setup();
    renderLogin();

    await user.type(screen.getByLabelText("이메일"), "a@b.com");

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByLabelText("비밀번호")).not.toHaveAttribute("aria-invalid");
  });

  it("다 채우면 눌러서 로그인할 수 있다", async () => {
    const user = userEvent.setup();
    vi.mocked(login).mockResolvedValue("token" as never);
    renderLogin();

    await user.type(screen.getByLabelText("이메일"), "a@b.com");
    await user.type(screen.getByLabelText("비밀번호"), "password123");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(login).toHaveBeenCalledTimes(1);
  });

  // 누르는 순간 disabled가 걸리면 방금 누른 버튼에서 초점이 <body>로 떨어져, 뒤이어 오는
  // 실패 문구를 "어디에 서 있는지" 모르는 채로 듣게 된다. aria-busy는 초점을 뺏지 않는다.
  it("요청 중에도 초점을 지키고 연타되지 않는다", async () => {
    const user = userEvent.setup();
    vi.mocked(login).mockReturnValue(pendingForever<string>());
    renderLogin();

    await user.type(screen.getByLabelText("이메일"), "a@b.com");
    await user.type(screen.getByLabelText("비밀번호"), "password123");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    const busyButton = await screen.findByRole("button", { name: "로그인 중…" });
    expect(busyButton).toHaveAttribute("aria-busy", "true");
    expect(busyButton).toHaveAttribute("aria-disabled", "true");
    expect(busyButton).toHaveFocus();

    // aria-disabled는 표시일 뿐 클릭을 막지 않는다 — 조기 반환이 없으면 같은 요청이 겹쳐 나간다.
    await user.click(busyButton);
    expect(login).toHaveBeenCalledTimes(1);
  });
});
