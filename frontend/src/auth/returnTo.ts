// 로그인이 필요해서 막힌 화면을 기억했다가, 로그인 직후 그 자리로 되돌려 보낸다.
//
// sessionStorage를 쓰는 이유는 소셜 로그인 때문이다. 카카오/구글 로그인은 브라우저가
// 문서를 통째로 갈아끼우는 전체 리다이렉트라, 라우터 state(Navigate의 state)나 메모리에
// 담아 둔 값은 왕복 도중 전부 사라진다. 이메일 로그인만 생각하면 라우터 state로 충분하지만
// 두 경로가 서로 다른 수단을 쓰면 "로그인 방식에 따라 복귀 지점이 달라지는" 차이가 생긴다.
// sessionStorage는 탭 단위라 탭을 닫으면 함께 사라지고, 다른 탭의 로그인에 끼어들지 않는다.

const RETURN_TO_KEY = "returnTo";

/**
 * 로그인 후 돌아갈 곳으로 저장한다. 라우터가 주는 경로만 넣도록 호출부를 한정한다.
 */
export function rememberReturnTo(path: string): void {
  sessionStorage.setItem(RETURN_TO_KEY, path);
}

/**
 * 저장해 둔 복귀 지점을 꺼내며 지운다. 없거나 수상하면 {@link fallback}을 준다.
 *
 * <p>한 번 쓰고 지우는 이유: 남겨두면 한참 뒤 사용자가 직접 /login으로 들어왔을 때
 * 옛날에 막혔던 화면으로 튕겨 나간다.
 *
 * <p>값을 검증하는 이유: sessionStorage는 같은 오리진의 어떤 스크립트든 쓸 수 있어
 * 여기 들어온 값을 우리가 넣었다고 단정할 수 없다. {@code //evil.com}처럼 슬래시 두 개로
 * 시작하는 값은 경로처럼 보이지만 프로토콜 상대 URL이라, 그대로 navigate하면 외부 사이트로
 * 나가는 오픈 리다이렉트가 된다. 역슬래시({@code /\evil.com})도 브라우저가 슬래시로 바꿔
 * 읽으므로 같은 취급을 한다. 앱 내부 경로는 "슬래시 하나 + 경로 문자"이므로 그 형태만 통과시킨다.
 */
export function consumeReturnTo(fallback: string): string {
  const stored = sessionStorage.getItem(RETURN_TO_KEY);
  sessionStorage.removeItem(RETURN_TO_KEY);

  return isInternalPath(stored) ? stored : fallback;
}

function isInternalPath(value: string | null): value is string {
  return value != null && /^\/(?![/\\])/.test(value);
}
