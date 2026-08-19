import { afterAll, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import ErrorBoundary from "./ErrorBoundary";

// React는 잡힌 예외도 콘솔에 그대로 흘린다. 막지 않으면 통과하는 실행에서도
// 스택 트레이스가 쏟아져 진짜 실패를 못 찾는다.
const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

function Boom(): never {
  // api/*.ts의 `res.data.data!`가 실제로 undefined를 받는 상황을 흉내낸다.
  throw new Error("Cannot read properties of undefined (reading 'menu')");
}

beforeEach(() => {
  consoleError.mockClear();
});

afterAll(() => {
  consoleError.mockRestore();
});

describe("ErrorBoundary", () => {
  it("예외가 없으면 자식을 그대로 보여준다", () => {
    render(
      <ErrorBoundary>
        <p>정상 화면</p>
      </ErrorBoundary>,
    );

    expect(screen.getByText("정상 화면")).toBeInTheDocument();
  });

  it("렌더 중 예외가 나면 백색 화면 대신 안내와 복구 수단을 보여준다", () => {
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );

    // 이게 없으면 React가 트리를 통째로 언마운트해 아무것도 남지 않는다.
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "새로고침" })).toBeInTheDocument();
  });

  it("원인을 콘솔에 남긴다", () => {
    // 로그가 없으면 백색 화면 제보를 받아도 어느 컴포넌트가 터졌는지 알 방법이 없다.
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );

    const ours = consoleError.mock.calls.filter((args) => String(args[0]).includes("ErrorBoundary"));
    expect(ours.length).toBeGreaterThan(0);
    expect(ours.flat().join(" ")).toContain("Cannot read properties of undefined");
  });
});
