import { http, unwrap, type ApiResponse } from "./http";
import type { KakaoPlace } from "./places";

/**
 * 여럿이 같이 뽑기.
 *
 * 참여(조회·제외·뽑기)는 링크만 있으면 된다 — 점심 자리에 있는 사람 전원이 가입해 있을
 * 리 없기 때문이다. 방 만들기와 결과 식당 선택은 호스트의 데이터를 쓰므로 로그인이 필요하다.
 */

export interface PickRoomMenu {
  id: number;
  name: string;
  /** 이 메뉴를 뺀 사람 수. 누가 뺐는지는 서버가 주지 않는다. */
  vetoedBy: number;
  vetoedByMe: boolean;
}

export interface PickRoomDecision {
  menuName: string;
  decidedAt: string;
  place: { name: string; url: string | null } | null;
}

export interface PickRoom {
  code: string;
  expiresAt: string;
  menus: PickRoomMenu[];
  participantCount: number;
  /** 아직 안 정해졌으면 null. 한 번 정해지면 바뀌지 않는다. */
  decision: PickRoomDecision | null;
  canChoosePlace: boolean;
}

export async function createPickRoom(categories?: string[]) {
  const res = await http.post<ApiResponse<PickRoom>>("/api/v1/pick/rooms", {
    categories: categories && categories.length > 0 ? categories : null,
  });
  return unwrap(res);
}

export async function fetchPickRoom(code: string, participant: string) {
  const res = await http.get<ApiResponse<PickRoom>>(`/api/v1/pick/rooms/${encodeURIComponent(code)}`, {
    params: { participant },
  });
  return unwrap(res);
}

/** 제외는 **전체 교체**다. 지금 빼 둔 것 전부를 매번 보낸다. */
export async function replacePickRoomVetoes(code: string, participant: string, vetoedMenuIds: number[]) {
  const res = await http.put<ApiResponse<PickRoom>>(
    `/api/v1/pick/rooms/${encodeURIComponent(code)}/vetoes`,
    { participant, vetoedMenuIds },
  );
  return unwrap(res);
}

export async function decidePickRoom(code: string) {
  const res = await http.post<ApiResponse<PickRoom>>(
    `/api/v1/pick/rooms/${encodeURIComponent(code)}/decide`,
  );
  return unwrap(res);
}

/** 방장이 카카오 검색 결과에서 정한 식당을 자기 픽 기록과 방 결과에 연결한다. */
export async function choosePickRoomPlace(code: string, place: KakaoPlace) {
  const res = await http.post<ApiResponse<PickRoom>>(
    `/api/v1/pick/rooms/${encodeURIComponent(code)}/place`,
    {
      name: place.place_name,
      address: place.road_address_name || place.address_name || null,
      phone: place.phone || null,
      latitude: Number(place.y),
      longitude: Number(place.x),
      naverUrl: place.place_url || null,
      kakaoPlaceId: place.id,
    },
  );
  return unwrap(res);
}

const PARTICIPANT_PREFIX = "menupick.room.participant.";

/**
 * 이 브라우저의 참가자 식별자를 방마다 하나씩 만들어 기억한다.
 *
 * <b>방마다 새로 만든다.</b> 하나를 여러 방에서 재사용하면 "같은 사람이 이 방들에 다 있었다"가
 * 서버 DB에 남는다. 계정과도 무관한 랜덤 값이라 이름·기기 정보가 들어가지 않는다.
 *
 * 저장소를 못 쓰는 환경(사생활 보호 모드 등)에서는 기억하지 못하고 매번 새로 만든다 —
 * 그 경우 새로고침하면 내가 뺀 것이 "남이 뺀 것"으로 보인다. 숫자는 맞고 내 표시만 사라진다.
 */
export function participantIdFor(code: string): string {
  const key = PARTICIPANT_PREFIX + code;
  try {
    const saved = window.localStorage.getItem(key);
    if (saved) return saved;
    const created = crypto.randomUUID();
    window.localStorage.setItem(key, created);
    return created;
  } catch {
    return crypto.randomUUID();
  }
}
