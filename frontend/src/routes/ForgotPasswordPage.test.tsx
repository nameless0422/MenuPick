import { beforeEach, describe, expect, it, vi } from "vitest";
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

/** 응답이 오지 않는 요청 — "진행 중" 상태를 붙잡아 두기 위한 것이다. */
function pendingForever<T>() {
  return new Promise<T>(() => {});
}

/**
 * 제출 버튼은 {@code disabled={isPending || !email.trim()}}였다.
 *
 * <p>이메일을 채우기 전에는 처음부터 disabled라 Tab 순회에서 통째로 빠진다 — 스크린리더
 * 사용자는 여기에 제출 버튼이 있다는 것도, 왜 제출이 안 되는지도 알 수 없었다. 입력의
 * required도 버튼이 disabled인 한 <b>브라우저 기본 검증조차 트리거하지 못한다.</b>
 *
 * <p>이 화면에서 막히는 이유는 "아직 안 채웠다" 하나뿐이고 그에 해당하는 문구가 화면에
 * 없다. 그래서 잠그는 대신 눌렀을 때 role="alert"로 알리고 그 칸으로 초점을 옮긴다.
 */
describe("ForgotPasswordPage 제출 버튼", () => {
  // 이 파일은 mock을 테스트마다 비우지 않는다 — 여기서는 "요청이 나갔는가"가 판정 기준이라
  // 앞 테스트의 호출이 섞이면 안 된다.
  beforeEach(() => {
    vi.mocked(requestPasswordReset).mockClear();
  });

  it("미입력이어도 버튼이 초점을 받는다", () => {
    renderForgot();

    const submit = screen.getByRole("button", { name: "재설정 링크 받기" });
    expect(submit).not.toBeDisabled();
    expect(submit).not.toHaveAttribute("aria-disabled");

    submit.focus();
    expect(submit).toHaveFocus();
  });

  it("빈 채로 누르면 무엇을 채워야 하는지 알리고 그 칸으로 초점을 옮긴다", async () => {
    const user = userEvent.setup();
    renderForgot();

    await user.click(screen.getByRole("button", { name: "재설정 링크 받기" }));

    expect(screen.getByRole("alert")).toHaveTextContent("이메일을 입력해주세요.");
    expect(screen.getByLabelText("이메일")).toHaveFocus();
    // 초점만 옮기고 이유를 칸에 묶지 않으면, 나중에 그 칸으로 되돌아왔을 때 다시 들을 길이 없다.
    expect(screen.getByLabelText("이메일")).toHaveAccessibleDescription("이메일을 입력해주세요.");
    expect(requestPasswordReset).not.toHaveBeenCalled();
  });

  // 제출 경로가 버튼 클릭만이 아니다 — 입력칸에서 Enter를 쳐도 같은 form이 제출된다.
  it("빈 채로 입력칸에서 Enter를 쳐도 발송 요청이 나가지 않는다", async () => {
    const user = userEvent.setup();
    renderForgot();

    await user.type(screen.getByLabelText("이메일"), "{Enter}");

    expect(requestPasswordReset).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("이메일을 입력해주세요.");
  });

  // 채우기 전부터 빨간 문구가 떠 있으면 안내가 아니라 방해다.
  it("누르기 전에는 빈 칸을 오류로 부르지 않는다", () => {
    renderForgot();

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByLabelText("이메일")).not.toHaveAttribute("aria-invalid");
  });

  // 누르는 순간 disabled가 걸리면 방금 누른 버튼에서 초점이 <body>로 떨어지고, 요청이 끝나
  // 다시 활성화돼도 돌아오지 않는다. aria-busy는 초점을 뺏지 않는다.
  it("요청 중에도 초점을 지키고 연타되지 않는다", async () => {
    const user = userEvent.setup();
    vi.mocked(requestPasswordReset).mockReturnValue(pendingForever<void>());
    renderForgot();

    await user.type(screen.getByLabelText("이메일"), "a@b.com");
    await user.click(screen.getByRole("button", { name: "재설정 링크 받기" }));

    const busyButton = await screen.findByRole("button", { name: "보내는 중…" });
    expect(busyButton).toHaveAttribute("aria-busy", "true");
    expect(busyButton).toHaveAttribute("aria-disabled", "true");
    expect(busyButton).toHaveFocus();

    // aria-disabled는 표시일 뿐 클릭을 막지 않는다 — 조기 반환이 없으면 같은 요청이 겹쳐 나간다.
    await user.click(busyButton);
    expect(requestPasswordReset).toHaveBeenCalledTimes(1);
  });
});
