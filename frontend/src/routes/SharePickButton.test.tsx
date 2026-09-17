import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import SharePickButton from "./SharePickButton";
import { sharePickMessage, sharePickUrl } from "./sharePick";

const ORIGIN = window.location.origin;

function stubShare(impl: (data: ShareData) => Promise<void>) {
  Object.defineProperty(navigator, "share", { value: vi.fn(impl), configurable: true });
}

function stubClipboard(impl: (text: string) => Promise<void>) {
  Object.defineProperty(navigator, "clipboard", {
    value: { writeText: vi.fn(impl) },
    configurable: true,
  });
}

function removeShare() {
  Reflect.deleteProperty(navigator, "share");
}

let clipboardImpl: (text: string) => Promise<void>;

beforeEach(() => {
  clipboardImpl = () => Promise.resolve();
});

afterEach(() => {
  removeShare();
  Reflect.deleteProperty(navigator, "clipboard");
});

const clickShare = async () => {
  // userEvent.setup()은 자기 클립보드 스텁을 navigator에 심는다. 그 뒤에 덮어써야 이 파일의
  // 스텁이 살아남는다 — 순서를 바꾸면 "is not a spy"로 죽는다.
  const user = userEvent.setup();
  stubClipboard(clipboardImpl);
  renderWithProviders(<SharePickButton menuName="김치찌개" />);
  await user.click(screen.getByRole("button", { name: "📤 공유하기" }));
};

describe("픽 결과 공유", () => {
  it("공유 시트를 쓸 수 있으면 메뉴 이름과 링크를 실어 연다", async () => {
    stubShare(() => Promise.resolve());
    await clickShare();

    await waitFor(() =>
      expect(navigator.share).toHaveBeenCalledWith({
        title: "메뉴픽",
        text: sharePickMessage("김치찌개"),
        url: sharePickUrl(ORIGIN),
      }),
    );
    // 공유 시트는 화면 밖에서 끝난다 — 여기서 더 할 말이 없다.
    expect(screen.queryByRole("status")).toBeNull();
  });

  /** 사용자가 시트를 닫은 것뿐이다. 오류를 띄우면 자기 행동을 고장으로 읽게 된다. */
  it("공유를 취소하면 아무 말도 하지 않는다", async () => {
    const abort = new Error("사용자 취소");
    abort.name = "AbortError";
    stubShare(() => Promise.reject(abort));
    await clickShare();

    await waitFor(() => expect(navigator.share).toHaveBeenCalled());
    expect(screen.queryByRole("status")).toBeNull();
    expect(screen.queryByRole("group", { name: "공유 문구 직접 복사" })).toBeNull();
    expect(navigator.clipboard.writeText).not.toHaveBeenCalled();
  });

  it("공유 시트가 없으면 클립보드에 넣고 그 사실을 알린다", async () => {
    removeShare();
    await clickShare();

    await waitFor(() =>
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith(
        `${sharePickMessage("김치찌개")}\n${sharePickUrl(ORIGIN)}`,
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("복사했어요");
  });

  it("공유 시트가 실패하면 복사로 내려간다", async () => {
    stubShare(() => Promise.reject(new Error("공유할 수 없음")));
    await clickShare();

    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalled());
    expect(await screen.findByRole("status")).toHaveTextContent("복사했어요");
  });

  /**
   * 이 서버는 자체 서명 인증서를 쓰고 있어 클립보드가 실제로 막힐 수 있다. 조용히 실패하면
   * 사용자는 버튼이 고장 난 줄 안다.
   */
  it("자동 복사가 막히면 문구를 띄워 직접 복사하게 한다", async () => {
    removeShare();
    clipboardImpl = () => Promise.reject(new Error("권한 없음"));
    await clickShare();

    const box = await screen.findByRole("textbox", { name: "공유 문구" });
    expect(box).toHaveValue(`${sharePickMessage("김치찌개")}\n${sharePickUrl(ORIGIN)}`);
    // Ctrl+C 한 번으로 끝나도록 미리 선택해 둔다.
    expect(box).toHaveFocus();
    expect((box as HTMLTextAreaElement).selectionEnd).toBeGreaterThan(0);
  });
});

describe("공유 문구", () => {
  /** 메신저 미리보기에서 잘려도 무엇이 뽑혔는지는 남아야 한다. */
  it("메뉴 이름이 링크보다 앞에 온다", () => {
    expect(sharePickMessage("김치찌개")).toContain("김치찌개");
    expect(sharePickMessage("김치찌개")).not.toContain("http");
  });

  /** 나중에 접근 로그에서 공유로 들어온 방문을 셀 수 있게 한다. */
  it("링크에 유입 표시를 붙인다", () => {
    expect(sharePickUrl("https://example.test")).toBe("https://example.test/?from=share");
  });
});
