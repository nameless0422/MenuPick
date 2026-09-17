/**
 * 뽑은 결과를 남에게 보내는 말과, 보내는 방법 고르기.
 *
 * <h2>왜 공유인가</h2>
 *
 * 2026-09-18 운영 기준 사용자 3명, 마지막 픽이 9월 13일이다. 앱 안에서 무엇을 더 만들어도
 * 볼 사람이 없다. 반면 "오늘 이거 뽑혔어"는 원래 남에게 말하게 되는 결과라, 공유는 이 앱에서
 * 가장 자연스러운 유입 경로다. 링크를 받은 사람은 로그인 화면의 "먼저 구경해보기"(게스트 데모
 * 픽)로 바로 한 번 뽑아볼 수 있다.
 */

/** 공유 문구. 메뉴 이름이 먼저 오게 한다 — 메신저 미리보기에서 잘려도 무엇이 뽑혔는지는 남는다. */
export function sharePickMessage(menuName: string): string {
  return `오늘 뭐 먹지? 메뉴픽이 "${menuName}" 뽑았어요 🎲`;
}

/**
 * 공유 링크.
 *
 * `from=share`를 붙이는 이유는 나중에 접근 로그에서 "공유로 들어온 방문"을 셀 수 있게 하기
 * 위해서다. 앱은 모르는 쿼리 파라미터를 무시하므로 화면 동작에는 영향이 없다.
 */
export function sharePickUrl(origin: string): string {
  return `${origin}/?from=share`;
}

export type ShareOutcome =
  /** 공유 시트나 앱으로 넘어갔다. 화면이 따로 알릴 것이 없다. */
  | { kind: "shared" }
  /** 클립보드에 넣었다. 사용자가 직접 붙여넣어야 하므로 그 사실을 알려야 한다. */
  | { kind: "copied"; text: string }
  /** 사용자가 공유 시트를 닫았다. 실패가 아니므로 오류를 띄우지 않는다. */
  | { kind: "cancelled" }
  /** 둘 다 못 했다. 화면이 문구를 직접 보여주고 고르게 한다. */
  | { kind: "manual"; text: string };

/**
 * 공유를 시도한다.
 *
 * <h2>왜 세 갈래인가</h2>
 *
 * <ul>
 *   <li>모바일 브라우저는 {@code navigator.share}로 카카오톡·메시지 등 설치된 앱을 띄운다.
 *       이게 가장 좋은 경로다.</li>
 *   <li>데스크톱 브라우저 상당수에는 그게 없다. 그때는 클립보드에 넣고 "복사했어요"라고 말한다.</li>
 *   <li>클립보드도 막힐 수 있다 — 보안 컨텍스트가 아니거나(http), 권한이 거부됐거나, 브라우저가
 *       지원하지 않는 경우다. <b>이 서버는 자체 서명 인증서를 쓰고 있어 실제로 자주 걸린다.</b>
 *       그때는 조용히 실패하지 않고 문구를 화면에 띄워 직접 복사하게 한다.</li>
 * </ul>
 *
 * 취소({@code AbortError})는 실패가 아니다. 사용자가 공유 시트를 닫은 것뿐인데 오류를 띄우면
 * 자기가 방금 한 행동을 고장으로 읽게 된다.
 */
export async function sharePick(menuName: string, origin: string): Promise<ShareOutcome> {
  const text = sharePickMessage(menuName);
  const url = sharePickUrl(origin);

  if (typeof navigator.share === "function") {
    try {
      await navigator.share({ title: "메뉴픽", text, url });
      return { kind: "shared" };
    } catch (error) {
      if (isAbort(error)) return { kind: "cancelled" };
      // 공유 시트가 못 뜬 것이므로 복사로 내려간다.
    }
  }

  const full = `${text}\n${url}`;
  try {
    await navigator.clipboard.writeText(full);
    return { kind: "copied", text: full };
  } catch {
    return { kind: "manual", text: full };
  }
}

/** 사용자가 공유 시트를 닫았을 때 브라우저가 주는 오류. 이름으로만 판정된다. */
function isAbort(error: unknown): boolean {
  return error instanceof Error && error.name === "AbortError";
}
