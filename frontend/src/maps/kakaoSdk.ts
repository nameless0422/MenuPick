/**
 * 카카오맵 JS SDK 로더.
 *
 * <p><b>VITE_KAKAO_JS_KEY는 번들에 실려 나가는 것이 정상이다.</b> 카카오맵 JS SDK는
 * 브라우저가 카카오 서버에 직접 붙는 구조라 키를 서버 뒤에 숨길 방법 자체가 없고,
 * 카카오도 이 키를 그렇게 쓰라고 준다 — 보호는 카카오 콘솔의 "플랫폼 &gt; Web 사이트 도메인"
 * 화이트리스트가 한다(등록되지 않은 도메인에서 부르면 카카오가 거부).
 *
 * <p>이건 REST API 키(백엔드의 KAKAO_REST_API_KEY)와 <b>다른 값이고 성격도 반대다.</b>
 * REST 키는 로컬 API(장소 검색) 자격증명을 겸해서, 노출되면 누구나 우리 검색 할당량을
 * 소진시킬 수 있고 도메인 화이트리스트로 막을 수도 없다. 그래서 REST 키는 서버에만 두고
 * 백엔드가 프록시한다(domain/kakao/KakaoLocalClient). 예전에 REST 키를 번들에서 걷어낸 건
 * 그 이유이지 "카카오 키는 프론트에 두면 안 된다"는 규칙이 있어서가 아니다 —
 * 그렇게 오해해서 JS 키까지 서버로 옮기면 지도는 뜨지 않는다.
 */

export interface KakaoLatLng {
  getLat(): number;
  getLng(): number;
}

export interface KakaoLatLngBounds {
  extend(latlng: KakaoLatLng): void;
}

export interface KakaoMapInstance {
  setCenter(latlng: KakaoLatLng): void;
  setBounds(bounds: KakaoLatLngBounds): void;
  setLevel(level: number): void;
  relayout(): void;
}

export interface KakaoMarker {
  setMap(map: KakaoMapInstance | null): void;
}

export interface KakaoInfoWindow {
  open(map: KakaoMapInstance, marker: KakaoMarker): void;
  close(): void;
}

/** 실제로 쓰는 것만 좁게 선언한다 — 통째로 any로 두면 오타가 런타임까지 살아남는다. */
export interface KakaoMaps {
  load(callback: () => void): void;
  LatLng: new (lat: number, lng: number) => KakaoLatLng;
  LatLngBounds: new () => KakaoLatLngBounds;
  Map: new (
    container: HTMLElement,
    options: { center: KakaoLatLng; level?: number },
  ) => KakaoMapInstance;
  Marker: new (options: { position: KakaoLatLng; title?: string }) => KakaoMarker;
  InfoWindow: new (options: {
    content: HTMLElement | string;
    removable?: boolean;
  }) => KakaoInfoWindow;
  event: {
    addListener(target: object, type: string, handler: () => void): void;
    removeListener(target: object, type: string, handler: () => void): void;
  };
}

declare global {
  interface Window {
    kakao?: { maps?: KakaoMaps };
  }
}

/** 키가 없어 아예 시도하지 않은 경우. 화면이 "로드 실패"와 다르게 안내하려고 구분한다. */
export const MISSING_KEY = "KAKAO_JS_KEY_MISSING";

export function kakaoJsKey(): string {
  return (import.meta.env.VITE_KAKAO_JS_KEY ?? "").trim();
}

// autoload=false: 스크립트가 붙자마자 SDK가 스스로 초기화하면 준비 완료 시점을 알 수 없다.
// 명시적으로 kakao.maps.load()를 부르고 그 콜백에서 resolve해야 Map 생성자의 존재가 보장된다.
const sdkUrl = (key: string) =>
  `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false`;

// 로드는 앱 전체에서 한 번뿐이어야 한다. 지도를 쓰는 화면이 둘 이상이라 컴포넌트마다
// 스크립트를 붙이면 SDK가 여러 번 초기화되면서 먼저 만들어 둔 지도가 깨진다.
let loadPromise: Promise<KakaoMaps> | null = null;

export function loadKakaoMaps(): Promise<KakaoMaps> {
  if (loadPromise) return loadPromise;

  const key = kakaoJsKey();
  if (!key) {
    // 빈 appkey로 붙으면 카카오는 그냥 거부하고 콘솔에 에러만 남긴다 — 사용자에게
    // "키를 설정해야 한다"고 말할 수 있는 신호가 되지 못하므로 아예 시도하지 않는다.
    // 캐시하지 않는다: 키의 유무는 빌드 산출물의 성질이라 재판정 비용이 0이다.
    return Promise.reject(new Error(MISSING_KEY));
  }

  // 다른 경로(HMR, 수동 삽입)로 이미 올라온 SDK가 있으면 그대로 쓴다.
  const loaded = window.kakao?.maps;
  if (loaded?.Map) {
    loadPromise = Promise.resolve(loaded);
    return loadPromise;
  }

  loadPromise = new Promise<KakaoMaps>((resolve, reject) => {
    const script = document.createElement("script");
    script.src = sdkUrl(key);
    script.async = true;

    const fail = (message: string) => {
      // 캐시를 비워 다음 마운트가 다시 시도할 수 있게 한다. 도메인 미등록처럼 영구적인
      // 실패라면 안내 문구가 다시 뜰 뿐이고, 일시적인 네트워크 오류라면 이걸로 회복된다.
      loadPromise = null;
      script.remove();
      reject(new Error(message));
    };

    script.onload = () => {
      const maps = window.kakao?.maps;
      // 앱키가 거부되면 카카오가 200에 에러 스크립트를 주기도 한다 — onerror가 아니라
      // "로드는 됐는데 kakao.maps가 없는" 모양으로 나타나므로 여기서도 확인해야 한다.
      if (!maps) {
        fail("카카오맵 SDK를 초기화하지 못했습니다.");
        return;
      }
      maps.load(() => resolve(maps));
    };
    script.onerror = () => fail("카카오맵 SDK를 불러오지 못했습니다.");

    document.head.appendChild(script);
  });

  return loadPromise;
}

/** 테스트 전용: 모듈 수준 로드 캐시를 비운다(테스트끼리 로드 상태가 새지 않게). */
export function resetKakaoSdkForTest() {
  loadPromise = null;
}
