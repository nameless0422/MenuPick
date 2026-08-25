import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import ResetPasswordPage from "./ResetPasswordPage";
import { confirmPasswordReset } from "../api/auth";
import { useAuth } from "../auth/AuthContext";

vi.mock("../api/auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/auth")>()),
  confirmPasswordReset: vi.fn(),
}));
vi.mock("../auth/AuthContext", () => ({ useAuth: vi.fn() }));

/** 응답이 오지 않는 요청 — "진행 중" 상태를 붙잡아 두기 위한 것이다. */
function pendingForever<T>() {
  return new Promise<T>(() => {});
}

/** 이 화면은 메일 링크의 착지점이라 토큰이 쿼리에 실려 온다 — 없으면 폼 자체가 그려지지 않는다. */
function renderReset(search = "?token=reset-token") {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/reset-password${search}`]}>
        <ResetPasswordPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.mocked(confirmPasswordReset).mockClear();
  vi.mocked(useAuth).mockReturnValue({
    isAuthenticated: false,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
    withdraw: vi.fn(),
    sessionExpired: false,
  });
});

/**
 * 제출 버튼은 {@code disabled={isPending || !canSubmit}}였다. 입력이 덜 찬 동안에는 처음부터
 * disabled라 Tab 순회에서 통째로 빠진다 — 스크린리더 사용자는 여기에 제출 버튼이 있다는 것도,
 * 왜 제출이 안 되는지도 알 수 없었다. 입력의 required도 버튼이 disabled인 한 <b>브라우저 기본
 * 검증조차 트리거하지 못해</b> 이유가 영영 나오지 않는다.
 *
 * <p>막히는 이유가 두 가지다. <b>형식이 틀렸다</b>(길이·불일치)는 이미 화면에 문구가 떠 있으니
 * 그 문구를 버튼에도 이어 붙이면 되고, <b>비었다</b>는 붙일 문구가 없으니 눌렀을 때
 * role="alert"로 알리고 그 칸으로 초점을 옮긴다.
 */
describe("ResetPasswordPage 제출 버튼", () => {
  /** 두 칸을 채운다. 확인 칸에 다른 값을 주면 불일치로 잠긴 상태를 만들 수 있다. */
  async function fillReset(
    user: ReturnType<typeof userEvent.setup>,
    password: string,
    passwordCheck: string,
  ) {
    if (password) await user.type(screen.getByLabelText("새 비밀번호"), password);
    if (passwordCheck) await user.type(screen.getByLabelText("새 비밀번호 확인"), passwordCheck);
    return screen.getByRole("button", { name: "비밀번호 변경" });
  }

  // 잠그되 초점은 남긴다. 잠긴 이유는 이미 칸 아래에 떠 있으므로 버튼 전용 문구를 새로
  // 만들지 않고 그것을 이어 붙인다 — 초점이 버튼에 닿는 순간 이름 뒤로 이유가 함께 읽힌다.
  it("형식이 어긋나면 잠기되 초점은 받고 이유가 함께 읽힌다", async () => {
    const user = userEvent.setup();
    renderReset();

    const submit = await fillReset(user, "correcthorse", "batterystaple");
    expect(submit).toHaveAttribute("aria-disabled", "true");
    expect(submit).not.toBeDisabled();

    submit.focus();
    expect(submit).toHaveFocus();
    expect(submit).toHaveAccessibleDescription(/비밀번호가 서로 다릅니다/);
  });

  // aria-disabled는 표시일 뿐 클릭을 막지 않는다 — 조기 반환이 없으면 그대로 서버로 나가고,
  // 확인하지 않은 비밀번호로 계정이 바뀐다.
  it("잠긴 채로 눌러도 변경 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderReset();

    await user.click(await fillReset(user, "correcthorse", "batterystaple"));

    expect(confirmPasswordReset).not.toHaveBeenCalled();
  });

  // 제출 경로가 버튼 클릭만이 아니다 — 입력칸에서 Enter를 쳐도 같은 form이 제출된다.
  it("잠긴 채로 입력칸에서 Enter를 쳐도 변경 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderReset();

    await fillReset(user, "correcthorse", "batterystaple");
    await user.type(screen.getByLabelText("새 비밀번호 확인"), "{Enter}");

    expect(confirmPasswordReset).not.toHaveBeenCalled();
  });

  it("빈 채로 누르면 비어 있는 칸을 모두 알리고 첫 칸으로 초점을 옮긴다", async () => {
    const user = userEvent.setup();
    renderReset();

    await user.click(screen.getByRole("button", { name: "비밀번호 변경" }));

    expect(screen.getAllByRole("alert").map((el) => el.textContent)).toEqual([
      "새 비밀번호를 입력해주세요.",
      "새 비밀번호를 한 번 더 입력해주세요.",
    ]);
    // 초점은 위에서 아래로 — 처음 만나는 빈 칸이 먼저 채워야 할 칸이다.
    expect(screen.getByLabelText("새 비밀번호")).toHaveFocus();
    // 초점만 옮기고 이유를 칸에 묶지 않으면, 그 칸으로 되돌아왔을 때 다시 들을 길이 없다.
    expect(screen.getByLabelText("새 비밀번호")).toHaveAccessibleDescription(
      "새 비밀번호를 입력해주세요.",
    );
    expect(confirmPasswordReset).not.toHaveBeenCalled();
  });

  // 확인 칸을 아예 건드리지 않으면 불일치로도 잡히지 않는다. 브라우저 기본 검증을 끈 이상
  // 이 칸이 비었는지는 이쪽에서 직접 봐야 한다 — 안 보면 확인 없이 비밀번호가 바뀐다.
  it("확인 칸만 비워 두고 눌러도 변경 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderReset();

    await user.click(await fillReset(user, "correcthorse", ""));

    expect(confirmPasswordReset).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("새 비밀번호를 한 번 더 입력해주세요.");
    expect(screen.getByLabelText("새 비밀번호 확인")).toHaveFocus();
  });

  // 채우기 전부터 빨간 문구가 떠 있으면 안내가 아니라 방해다.
  it("누르기 전에는 빈 칸을 오류로 부르지 않는다", () => {
    renderReset();

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByLabelText("새 비밀번호")).not.toHaveAttribute("aria-invalid");
  });

  it("다 채우면 눌러서 비밀번호를 바꿀 수 있다", async () => {
    const user = userEvent.setup();
    vi.mocked(confirmPasswordReset).mockResolvedValue("access-token");
    renderReset();

    const submit = await fillReset(user, "correcthorse", "correcthorse");
    expect(submit).not.toHaveAttribute("aria-disabled");
    // 사유가 사라진 뒤에도 참조가 남아 있으면 스크린리더가 빈 설명을 읽는다.
    expect(submit).not.toHaveAttribute("aria-describedby");

    await user.click(submit);
    expect(confirmPasswordReset).toHaveBeenCalledWith("reset-token", "correcthorse");
  });

  // 누르는 순간 disabled가 걸리면 방금 누른 버튼에서 초점이 <body>로 떨어지고, 요청이 끝나
  // 다시 활성화돼도 돌아오지 않는다. aria-busy는 초점을 뺏지 않는다.
  it("요청 중에도 초점을 지키고 연타되지 않는다", async () => {
    const user = userEvent.setup();
    vi.mocked(confirmPasswordReset).mockReturnValue(pendingForever<string>());
    renderReset();

    await user.click(await fillReset(user, "correcthorse", "correcthorse"));

    const busyButton = await screen.findByRole("button", { name: "변경 중…" });
    expect(busyButton).toHaveAttribute("aria-busy", "true");
    expect(busyButton).toHaveAttribute("aria-disabled", "true");
    expect(busyButton).toHaveFocus();

    await user.click(busyButton);
    expect(confirmPasswordReset).toHaveBeenCalledTimes(1);
  });
});

/** 토큰이 없으면 폼을 그리지 않는다 — 이 화면에서 되살릴 수 없으니 다시 받는 길만 남긴다. */
describe("ResetPasswordPage 토큰 없음", () => {
  it("링크가 잘못됐음을 알리고 다시 받는 길을 준다", () => {
    renderReset("");

    expect(screen.getByRole("heading", { name: "링크가 올바르지 않습니다" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "재설정 링크 다시 받기" })).toHaveAttribute(
      "href",
      "/forgot-password",
    );
  });
});
