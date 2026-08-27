import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync } from "node:fs";
import { join, relative, sep } from "node:path";

/**
 * CSS 소스 자체를 읽는 테스트.
 *
 * <p>보통 스타일은 테스트로 잡지 않는다 — {@code vite.config.ts}가 {@code css: false}이고
 * jsdom은 레이아웃을 계산하지 않아서, 색이 맞는지 여백이 맞는지는 확인할 방법이 없다.
 * 이 파일이 보는 것은 <b>렌더 결과가 아니라 규약</b>이다: 글자 크기를 px로 적지 않는다는 약속.
 *
 * <p>그 약속은 눈으로 지켜지지 않는다. px로 적어도 우리 화면은 멀쩡하고, 깨지는 것은 브라우저
 * 기본 글꼴 크기를 키워 둔 사용자에게서만이다 — 개발자 화면에서는 아무 일도 일어나지 않으므로
 * 리뷰에서도 QA에서도 드러나지 않는다. 그래서 사람 대신 이 테스트가 지킨다.
 *
 * <p>{@code min-width}/{@code min-height} 같은 값은 대상이 아니다. 2.5.8이 요구하는 24px은
 * CSS 픽셀 단위의 물리적 보장이라, rem으로 바꾸면 기본 글꼴을 <b>줄여</b> 둔 사용자에게서
 * 그 보장이 함께 줄어든다. 근거는 index.css의 "글자 크기" 주석.
 *
 * <h2>왜 src/ 밖에 있나</h2>
 *
 * <p>이 검사는 파일을 파일로 읽어야 한다. Vite의 {@code import.meta.glob(..., '?raw')}로는
 * 안 된다 — {@code css: false}이면 CSS 임포트가 <b>빈 문자열</b>로 돌아와서, 규약이 지켜져서가
 * 아니라 아무것도 안 봐서 초록불이 되는 테스트가 만들어진다(실제로 그렇게 한 번 통과했다).
 * 그래서 {@code node:fs}를 쓰는데, 그러려면 node 타입이 필요하고 그것을 src의
 * tsconfig(types: ["vite/client"])에 열어 주면 앱 코드 어디서나 {@code process}가 잡혀
 * 브라우저 번들에 들어갈 수 없는 코드가 타입 검사를 통과한다. 그래서 이 파일만
 * tsconfig.node.json 쪽에 둔다.
 */
const SRC = join(process.cwd(), "src");

function cssFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry): string[] => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return cssFiles(path);
    return entry.name.endsWith(".css") ? [path] : [];
  });
}

/**
 * 주석을 공백으로 덮는다. 주석 안의 "13px"은 그 값이 원래 무엇이었는지 적어 둔 기록이지
 * 선언이 아니다. 줄바꿈은 남겨야 아래에서 세는 줄 번호가 실제 파일과 맞는다.
 */
function withoutComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, " "));
}

// font-size: 와 font: 단축 속성만 잡는다. font-family:·transition: font-size 는 걸리지 않는다.
//
// 값 쪽은 \bpx\b가 아니라 \dpx\b다. "13px"에서 px 앞 글자는 숫자 3이고 둘 다 단어 문자라
// 그 사이에는 단어 경계가 없다 — \b로 적으면 정작 잡아야 할 선언이 전부 빠져나가고,
// 위반이 있어서가 아니라 정규식이 못 봐서 통과하는 테스트가 된다. 실제로 그렇게 한 번
// 통과했다: 이 파일이 처음 잡아낸 결함은 CSS가 아니라 이 파일 자신이었다.
const PX_FONT_SIZE = /(?:^|[\s;{])font(?:-size)?\s*:[^;}]*\dpx\b/gi;

describe("스타일 규약", () => {
  it("글자 크기를 px로 적은 곳이 없다", () => {
    const files = cssFiles(SRC);
    // 파일을 못 읽었는데 조용히 통과하면 규약이 아니라 침묵을 검증하게 된다.
    expect(files.length).toBeGreaterThan(5);

    const offenders: string[] = [];
    for (const file of files) {
      const css = withoutComments(readFileSync(file, "utf8"));
      expect(css.length).toBeGreaterThan(0);
      for (const match of css.matchAll(PX_FONT_SIZE)) {
        const line = css.slice(0, match.index).split("\n").length;
        // 경로 구분자를 슬래시로 맞춘다 — 실패 메시지는 사람이 읽고 바로 그 줄로 가는 용도라
        // 윈도우에서만 역슬래시로 나오면 편집기·터미널에 그대로 붙여넣을 수 없다.
        const where = relative(process.cwd(), file).split(sep).join("/");
        offenders.push(`${where}:${line}  ${match[0].trim()}`);
      }
    }

    expect(offenders).toEqual([]);
  });

  it("루트는 글자 크기를 정하지 않고 사용자 기본값을 그대로 쓴다", () => {
    // 하위의 rem이 무엇을 기준으로 계산되는지가 여기서 정해진다. :root에 본문 크기를
    // 얹으면 1rem이 "사용자가 정한 값"이 아니라 "본문 크기"가 되어, 아래 모든 rem 값의
    // 뜻이 조용히 바뀐다 — 화면은 그대로라 바뀐 줄도 모른다.
    const css = withoutComments(readFileSync(join(SRC, "index.css"), "utf8"));
    const root = css.slice(css.indexOf(":root {"), css.indexOf("@media (prefers-color-scheme"));

    expect(root).toMatch(/font-size:\s*100%\s*;/);
  });
});
