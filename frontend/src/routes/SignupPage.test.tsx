import { describe, expect, it, vi } from "vitest";
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
