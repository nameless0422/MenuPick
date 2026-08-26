/**
 * 사용자 데이터에서 온 URL을 링크로 걸어도 되는지 판정한다.
 *
 * `naverUrl`(식당 상세)은 정상 경로에서는 카카오 장소 검색이 준 https 주소만 들어간다.
 * 하지만 그건 화면이 그렇게 채워준다는 뜻일 뿐, 값 자체는 사용자가 정하는 필드다 —
 * API를 직접 호출해 `javascript:alert(document.cookie)`를 저장하면 그 문자열이 그대로
 * `href`가 되고, 클릭 한 번에 우리 오리진에서 스크립트가 돈다(self-XSS). 지금은 자기
 * 데이터만 자기가 보므로 피해가 자신에게 갇히지만, 공유·추천처럼 남의 식당이 내 화면에
 * 그려지는 기능이 하나 붙는 순간 그대로 저장형 XSS가 된다.
 *
 * React의 JSX 이스케이프는 여기서 아무 도움이 되지 않는다 — 이스케이프는 텍스트 노드를
 * 지킬 뿐이고, `href`에 들어간 `javascript:`는 이스케이프할 마크업이 없다.
 *
 * 백엔드도 같은 제약을 건다(RestaurantRequest). 양쪽에 두는 이유는 이미 저장된 값과
 * 앞으로 들어올 값이 서로 다른 문제이기 때문이다: 백엔드 검증은 새 저장만 막고,
 * 이 함수는 그 이전에 들어온 행까지 막는다.
 */

/** 링크로 허용하는 스킴. 이 목록에 없는 건 전부 링크를 걸지 않는다. */
const SAFE_PROTOCOLS = ["http:", "https:"];

export function safeExternalUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  let parsed: URL;
  try {
    // 상대 경로("/menus")도 통과시키지 않기 위해 base를 주지 않는다 — 스킴이 없으면 여기서
    // 던진다. 외부 링크 자리에 상대 경로가 오는 건 어차피 정상 데이터가 아니다.
    parsed = new URL(url);
  } catch {
    return null;
  }
  // 스킴 비교는 파싱된 protocol로만 한다. 문자열 앞부분을 보는 방식은
  // " javascript:"(선행 공백)·"JaVaScRiPt:"·"java\tscript:"에 전부 뚫린다 —
  // 브라우저는 href를 읽을 때 그것들을 모두 javascript로 정규화한다.
  return SAFE_PROTOCOLS.includes(parsed.protocol) ? parsed.href : null;
}
