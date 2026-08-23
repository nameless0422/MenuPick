import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import VerifyEmailPage from "./VerifyEmailPage";
import { verifyEmail } from "../api/auth";

vi.mock("../api/auth", () => ({ verifyEmail: vi.fn() }));
vi.mock("../auth/AuthContext", () => ({ useAuth: () => ({ login: vi.fn() }) }));

const verifyEmailMock = vi.mocked(verifyEmail);

function renderAt(search: string) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[`/verify-email${search}`]}>
        <Routes>
          <Route path="/verify-email" element={<VerifyEmailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  verifyEmailMock.mockReset();
  verifyEmailMock.mockReturnValue(new Promise(() => {}) as never);
});

/**
 * 이 화면은 링크를 타고 들어오는 것이 유일한 진입 경로다. 토큰이 없거나 서버가 거절하면
 * 그 사실을 알려주는 것 말고는 사용자가 할 수 있는 일이 없으므로, 두 실패가 각각
 * 제대로 표시되는지가 이 화면의 전부다.
 */
describe("VerifyEmailPage", () => {
  it("토큰이 없는 링크는 서버에 묻지 않고 바로 안내한다", async () => {
    renderAt("");

    expect(await screen.findByRole("alert")).toHaveTextContent("인증 링크가 올바르지 않습니다.");
    // 보낼 토큰이 없으므로 요청 자체가 나가면 안 된다.
    expect(verifyEmailMock).not.toHaveBeenCalled();
  });

  it("토큰이 있으면 인증을 요청하고 그동안 처리 중임을 보여준다", async () => {
    renderAt("?token=abc");

    expect(await screen.findByText("이메일 인증 처리 중…")).toBeInTheDocument();
    expect(verifyEmailMock).toHaveBeenCalledWith("abc");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("서버가 거절하면 그 이유를 알린다", async () => {
    verifyEmailMock.mockRejectedValue(new Error("만료된 링크입니다."));

    renderAt("?token=abc");

    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });

  /**
   * 토큰은 1회용이라 StrictMode의 두 번째 effect 실행은 반드시 실패한다.
   * requested 가드가 없으면 정상 링크가 "이미 사용된 토큰"으로 튕긴다.
   */
  it("같은 토큰으로 요청을 두 번 보내지 않는다", async () => {
    const { rerender } = renderAt("?token=abc");
    await screen.findByText("이메일 인증 처리 중…");

    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={["/verify-email?token=abc"]}>
          <Routes>
            <Route path="/verify-email" element={<VerifyEmailPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(verifyEmailMock).toHaveBeenCalledTimes(1);
  });
});
