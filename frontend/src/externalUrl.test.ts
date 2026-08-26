import { describe, expect, it } from "vitest";
import { safeExternalUrl } from "./externalUrl";

describe("safeExternalUrl", () => {
  it("http·https 주소는 그대로 통과시킨다", () => {
    expect(safeExternalUrl("https://place.map.kakao.com/12345")).toBe(
      "https://place.map.kakao.com/12345",
    );
    expect(safeExternalUrl("http://place.map.kakao.com/12345")).toBe(
      "http://place.map.kakao.com/12345",
    );
  });

  it("빈 값은 링크 없음으로 본다", () => {
    expect(safeExternalUrl(null)).toBeNull();
    expect(safeExternalUrl(undefined)).toBeNull();
    expect(safeExternalUrl("")).toBeNull();
  });

  // href에 들어간 javascript:는 클릭 한 번에 우리 오리진에서 스크립트를 돌린다.
  // React의 JSX 이스케이프는 텍스트 노드만 지키므로 여기서는 아무 도움이 되지 않는다.
  it.each([
    "javascript:alert(1)",
    "JaVaScRiPt:alert(1)",
    "  javascript:alert(1)",
    "java\tscript:alert(1)",
    "java\nscript:alert(1)",
    "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
    "vbscript:msgbox(1)",
  ])("%s 같은 스킴은 링크로 걸지 않는다", (hostile) => {
    expect(safeExternalUrl(hostile)).toBeNull();
  });

  it("스킴이 없는 값도 걸러낸다", () => {
    // 외부 링크 자리에 상대 경로가 오는 것은 어차피 정상 데이터가 아니다.
    expect(safeExternalUrl("/menus")).toBeNull();
    expect(safeExternalUrl("place.map.kakao.com/12345")).toBeNull();
  });
});
