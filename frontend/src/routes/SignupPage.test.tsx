import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import SignupPage from "./SignupPage";
import { resendVerification, signup } from "../api/auth";

vi.mock("../api/auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/auth")>()),
  signup: vi.fn(),
  resendVerification: vi.fn(),
}));

function renderSignup() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <SignupPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * 이 폼은 {@code <label>}이 input을 감싸는 방식이라 접근 가능한 이름은 잘 붙지만,
 * 검증 메시지는 label 바깥의 형제 {@code <p>}였다. 그 칸으로 이동한 스크린리더
 * 사용자에게는 오류가 있다는 사실 자체가 전달되지 않았다.
 *
 * 반대로 이 메시지에 role="alert"를 붙여서도 안 된다. 타이핑하는 동안 나타났다
 * 사라지므로 글자마다 낭독을 가로챈다. aria-describedby가 맞는 도구다.
 */
describe("SignupPage 비밀번호 검증 안내", () => {
  it("길이 미달 메시지가 비밀번호 칸에 연결된다", async () => {
    const user = userEvent.setup();
    renderSignup();

    const password = screen.getByLabelText("비밀번호");
    await user.type(password, "short");

    const message = screen.getByText(/자 이상이어야 합니다/);
    expect(password).toHaveAttribute("aria-invalid", "true");
    expect(password).toHaveAttribute("aria-describedby", message.id);
    expect(message.id).toBeTruthy();
  });

  it("불일치 메시지가 확인 칸에 연결된다", async () => {
    const user = userEvent.setup();
    renderSignup();

    await user.type(screen.getByLabelText("비밀번호"), "correcthorse");
    const check = screen.getByLabelText("비밀번호 확인");
    await user.type(check, "batterystaple");

    const message = screen.getByText("비밀번호가 서로 다릅니다.");
    expect(check).toHaveAttribute("aria-invalid", "true");
    expect(check).toHaveAttribute("aria-describedby", message.id);
  });

  it("오류가 없으면 aria-invalid를 남기지 않는다", async () => {
    const user = userEvent.setup();
    renderSignup();

    const password = screen.getByLabelText("비밀번호");
    await user.type(password, "correcthorse");

    expect(password).not.toHaveAttribute("aria-invalid");
    expect(password).not.toHaveAttribute("aria-describedby");
  });

  it("검증 메시지는 alert가 아니다 — 타이핑을 가로채면 안 된다", async () => {
    const user = userEvent.setup();
    renderSignup();

    await user.type(screen.getByLabelText("비밀번호"), "short");

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

/**
 * 가입에 성공하면 폼이 통째로 안내 화면으로 갈린다 — 방금 누른 "가입하기" 버튼이 사라져
 * 브라우저가 초점을 {@code <body>}로 되돌린다.
 *
 * <p>인증 메일을 확인해야 로그인이 되는 구조(auth.ts의 EMAIL_NOT_VERIFIED)라, 이 화면의
 * 안내를 놓치면 <b>가입 직후 로그인 실패 루프</b>에 빠진다.
 */
describe("가입 성공 화면 전환", () => {
  async function submitSignup() {
    const user = userEvent.setup();
    vi.mocked(signup).mockResolvedValue(undefined as never);
    renderSignup();

    await user.type(screen.getByLabelText("이메일"), "a@b.com");
    await user.type(screen.getByLabelText("닉네임"), "테스터");
    await user.type(screen.getByLabelText("비밀번호"), "password123");
    await user.type(screen.getByLabelText("비밀번호 확인"), "password123");
    await user.click(screen.getByRole("button", { name: "가입하기" }));

    return user;
  }

  it("초점이 새 화면의 제목으로 간다", async () => {
    await submitSignup();

    const heading = await screen.findByRole("heading", { name: "메일을 확인해주세요" });
    await waitFor(() => expect(heading).toHaveFocus());
  });

  it("재발송에 성공해도 버튼이 사라지지 않고 결과가 통지된다", async () => {
    vi.mocked(resendVerification).mockResolvedValue(undefined as never);
    const user = await submitSignup();

    const resend = await screen.findByRole("button", { name: "메일이 안 왔어요" });
    await user.click(resend);

    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent(/인증 메일을 다시 보냈어요/),
    );
    expect(screen.getByRole("button", { name: "메일이 안 왔어요" })).toHaveFocus();
  });
});

/** 응답이 오지 않는 요청 — "진행 중" 상태를 붙잡아 두기 위한 것이다. */
function pendingForever<T>() {
  return new Promise<T>(() => {});
}

/**
 * 제출 버튼은 {@code disabled={isPending || !canSubmit}}였다. 입력이 덜 찬 동안에는 처음부터
 * disabled라 Tab 순회에서 통째로 빠진다 — 스크린리더 사용자는 가입 버튼이 있다는 것도,
 * 왜 가입이 안 되는지도 알 수 없었다.
 *
 * <p>여기서는 막히는 이유가 두 가지다. <b>형식이 틀렸다</b>(길이·불일치)는 이미 화면에 문구가
 * 떠 있으니 그 문구를 버튼에도 이어 붙이면 되고, <b>비었다</b>는 붙일 문구가 없으니 눌렀을 때
 * role="alert"로 알리고 그 칸으로 초점을 옮긴다.
 */
describe("SignupPage 제출 버튼", () => {
  // 이 파일의 다른 describe들은 호출 횟수를 보지 않아 mock을 비우지 않고 지나간다.
  // 여기서는 "요청이 나갔는가"가 판정 기준이라 앞 테스트의 호출이 섞이면 안 된다.
  beforeEach(() => {
    vi.mocked(signup).mockClear();
  });

  /** 네 칸을 채운다. 확인 칸에 다른 값을 주면 불일치로 잠긴 상태를 만들 수 있다. */
  async function fillSignup(
    user: ReturnType<typeof userEvent.setup>,
    password: string,
    passwordCheck: string,
  ) {
    await user.type(screen.getByLabelText("이메일"), "a@b.com");
    await user.type(screen.getByLabelText("닉네임"), "테스터");
    await user.type(screen.getByLabelText("비밀번호"), password);
    if (passwordCheck) await user.type(screen.getByLabelText("비밀번호 확인"), passwordCheck);
    return screen.getByRole("button", { name: "가입하기" });
  }

  // 잠그되 초점은 남긴다. 잠긴 이유는 이미 칸 아래에 떠 있으므로 버튼 전용 문구를 새로
  // 만들지 않고 그것을 이어 붙인다 — 초점이 버튼에 닿는 순간 이름 뒤로 이유가 함께 읽힌다.
  it("형식이 어긋나면 잠기되 초점은 받고 이유가 함께 읽힌다", async () => {
    const user = userEvent.setup();
    renderSignup();

    const submit = await fillSignup(user, "correcthorse", "batterystaple");
    expect(submit).toHaveAttribute("aria-disabled", "true");
    expect(submit).not.toBeDisabled();

    submit.focus();
    expect(submit).toHaveFocus();
    expect(submit).toHaveAccessibleDescription(/비밀번호가 서로 다릅니다/);
  });

  // aria-disabled는 표시일 뿐 클릭을 막지 않는다 — 조기 반환이 없으면 그대로 서버로 나간다.
  it("잠긴 채로 눌러도 가입 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderSignup();

    await user.click(await fillSignup(user, "correcthorse", "batterystaple"));

    expect(signup).not.toHaveBeenCalled();
  });

  // 제출 경로가 버튼 클릭만이 아니다 — 입력칸에서 Enter를 쳐도 같은 form이 제출된다.
  it("잠긴 채로 입력칸에서 Enter를 쳐도 가입 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderSignup();

    await fillSignup(user, "correcthorse", "batterystaple");
    await user.type(screen.getByLabelText("비밀번호 확인"), "{Enter}");

    expect(signup).not.toHaveBeenCalled();
  });

  it("빈 채로 누르면 비어 있는 칸을 모두 알리고 첫 칸으로 초점을 옮긴다", async () => {
    const user = userEvent.setup();
    renderSignup();

    await user.click(screen.getByRole("button", { name: "가입하기" }));

    expect(screen.getAllByRole("alert").map((el) => el.textContent)).toEqual([
      "이메일을 입력해주세요.",
      "닉네임을 입력해주세요.",
      "비밀번호를 입력해주세요.",
      "비밀번호를 한 번 더 입력해주세요.",
    ]);
    // 초점은 위에서 아래로 — 처음 만나는 빈 칸이 먼저 채워야 할 칸이다.
    expect(screen.getByLabelText("이메일")).toHaveFocus();
    expect(screen.getByLabelText("이메일")).toHaveAccessibleDescription("이메일을 입력해주세요.");
    expect(signup).not.toHaveBeenCalled();
  });

  // 확인 칸을 아예 건드리지 않으면 불일치로도 잡히지 않는다. 브라우저 기본 검증을 끈 이상
  // 이 칸이 비었는지는 이쪽에서 직접 봐야 한다 — 안 보면 확인 없이 가입 요청이 나간다.
  it("확인 칸만 비워 두고 눌러도 가입 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderSignup();

    await user.click(await fillSignup(user, "correcthorse", ""));

    expect(signup).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("비밀번호를 한 번 더 입력해주세요.");
    expect(screen.getByLabelText("비밀번호 확인")).toHaveFocus();
  });

  // 채우기 전부터 빨간 문구가 떠 있으면 안내가 아니라 방해다.
  it("누르기 전에는 빈 칸을 오류로 부르지 않는다", () => {
    renderSignup();

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByLabelText("이메일")).not.toHaveAttribute("aria-invalid");
  });

  // 누르는 순간 disabled가 걸리면 방금 누른 버튼에서 초점이 <body>로 떨어지고, 요청이 끝나
  // 다시 활성화돼도 돌아오지 않는다. aria-busy는 초점을 뺏지 않는다.
  it("요청 중에도 초점을 지키고 연타되지 않는다", async () => {
    const user = userEvent.setup();
    vi.mocked(signup).mockReturnValue(pendingForever<void>());
    renderSignup();

    await user.click(await fillSignup(user, "correcthorse", "correcthorse"));

    const busyButton = await screen.findByRole("button", { name: "가입 중…" });
    expect(busyButton).toHaveAttribute("aria-busy", "true");
    expect(busyButton).toHaveAttribute("aria-disabled", "true");
    expect(busyButton).toHaveFocus();

    await user.click(busyButton);
    expect(signup).toHaveBeenCalledTimes(1);
  });
});
