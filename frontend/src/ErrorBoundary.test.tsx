import { afterAll, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import ErrorBoundary from "./ErrorBoundary";

// React는 잡힌 예외도 콘솔에 그대로 흘린다. 막지 않으면 통과하는 실행에서도
// 스택 트레이스가 쏟아져 진짜 실패를 못 찾는다.
const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

function Boom(): never {
  // 컴포넌트가 없는 값을 읽다 터지는, 렌더 중 예외의 가장 흔한 모양을 흉내낸다.
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

/**
 * 예외가 나면 기존 트리가 통째로 언마운트되어 초점이 <body>로 떨어진다. 그러면 스크린리더의
 * 읽기 커서도 문서 맨 위로 돌아가고, 키보드 사용자는 Tab을 눌러 지금 화면에 무엇이 남았는지
 * 더듬어야 한다. 여기는 라우터 밖이라 <main> 랜드마크도 없어 되돌아올 자리조차 없다.
 */
describe("ErrorBoundary 초점", () => {
  it("오류 화면의 제목으로 초점을 옮긴다", () => {
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );

    expect(screen.getByRole("heading", { name: "화면을 표시하지 못했습니다" })).toHaveFocus();
  });

  it("제목은 Tab 순회에 끼어들지 않는다", () => {
    // 초점을 받게 하려고 tabIndex=0을 주면 이후 모든 Tab 순회에 제목이 하나 더 늘어난다.
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );

    expect(screen.getByRole("heading", { name: "화면을 표시하지 못했습니다" })).toHaveAttribute(
      "tabindex",
      "-1",
    );
  });
});
