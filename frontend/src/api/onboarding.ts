import { http, unwrap, type ApiResponse } from "./http";

/**
 * 첫 사용자 안내.
 *
 * 서버는 "온보딩 완료" 값을 따로 저장하지 않고 이미 있는 데이터로 판정한다 — 픽한 적이 있거나
 * 제외해 둔 메뉴가 하나라도 있으면 `needed`가 false다. 그래서 이 응답은 늘 지금 상태를 말한다.
 */

export interface OnboardingMenu {
  id: number;
  name: string;
  categories: string[];
}

export interface OnboardingStatus {
  /** 아직 아무것도 하지 않은 새 사용자인가. */
  needed: boolean;
  menus: OnboardingMenu[];
}

export async function fetchOnboardingStatus() {
  const res = await http.get<ApiResponse<OnboardingStatus>>("/api/v1/onboarding");
  return unwrap(res);
}

const SKIP_KEY = "menupick.onboarding.skipped";

/**
 * "지금은 건너뛰기"는 브라우저에만 남긴다.
 *
 * 서버에 컬럼을 하나 두면 마이그레이션이 늘고, 그 값이 실제 사용 상태와 어긋나는 경우를 따로
 * 다뤄야 한다. 대가는 기기를 바꾸면 한 번 더 보인다는 것인데, 한 번이라도 픽하면 서버 판정
 * (`needed=false`)이 이겨서 그 뒤로는 영영 사라진다.
 */
export function isOnboardingSkipped(): boolean {
  try {
    return window.localStorage.getItem(SKIP_KEY) === "1";
  } catch {
    return false;
  }
}

export function skipOnboarding(): void {
  try {
    window.localStorage.setItem(SKIP_KEY, "1");
  } catch {
    // 저장하지 못해도 이번 화면에서는 닫힌다. 다음에 한 번 더 보일 뿐이다.
  }
}
