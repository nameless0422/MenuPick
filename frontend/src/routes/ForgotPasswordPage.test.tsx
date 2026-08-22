import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import ForgotPasswordPage from "./ForgotPasswordPage";
import { requestPasswordReset } from "../api/auth";

vi.mock("../api/auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/auth")>()),
  requestPasswordReset: vi.fn(),
}));

function renderForgot() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ForgotPasswordPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * 발송에 성공하면 폼이 통째로 안내 화면으로 갈린다 — 방금 누른 버튼이 사라져 초점이
 * {@code <body>}로 떨어진다.
 *
 * <p>게다가 {@code <h1>}은 "비밀번호 재설정" 그대로라, 제목만으로는 화면이 바뀐 것을
 * 알 방법조차 없었다.
 */
describe("발송 완료 화면 전환", () => {
  async function submit() {
    const user = userEvent.setup();
    vi.mocked(requestPasswordReset).mockResolvedValue(undefined as never);
    renderForgot();

    await user.type(screen.getByLabelText("이메일"), "a@b.com");
    await user.click(screen.getByRole("button", { name: "재설정 링크 받기" }));
    return user;
  }

  it("바뀐 화면에 제목이 생기고 초점이 그리로 간다", async () => {
    await submit();

    const heading = await screen.findByRole("heading", { name: "메일을 확인해주세요" });
    await waitFor(() => expect(heading).toHaveFocus());
  });

  it("계정 존재 여부를 흘리지 않는 문구는 그대로 유지한다", async () => {
    await submit();

    // "보냈다"가 아니라 "가입돼 있다면 보냈다"여야 사실과 맞고, 열거도 막는다.
    expect(await screen.findByText(/가입된 계정이 있다면 재설정 링크를 보냈습니다/)).toBeInTheDocument();
  });
});
