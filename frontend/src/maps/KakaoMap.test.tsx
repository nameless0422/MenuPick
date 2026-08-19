import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import KakaoMap, { type KakaoMapPoint } from "./KakaoMap";
import { resetKakaoSdkForTest, type KakaoMaps } from "./kakaoSdk";

/**
 * 이 파일이 지키는 계약은 하나다: <b>지도는 실패해도 앱을 깨뜨리지 않는다.</b>
 *
 * <p>운영 서버에는 아직 VITE_KAKAO_JS_KEY가 없다. 그 상태로 배포됐을 때 식당 화면과
 * 픽 화면이 통째로 죽으면 지도를 얹은 것 자체가 손해다. 그래서 "키 없음"과 "로드 실패"를
 * 각각 렌더까지 태워 확인한다.
 */

// jsdom은 <script src>를 실제로 받아오지 않는다(onload/onerror가 영영 안 온다).
// 그래서 head에 붙는 순간을 가로채 테스트가 성공/실패 시점을 직접 정한다.
let appended: HTMLScriptElement[] = [];
let appendSpy: ReturnType<typeof vi.spyOn>;

const POINTS: KakaoMapPoint[] = [
  { id: 1, name: "진주회관", address: "서울시 중구", latitude: 37.5665, longitude: 126.978 },
  { id: 2, name: "을지면옥", address: "서울시 중구", latitude: 37.566, longitude: 126.99 },
];

function setKey(value: string | undefined) {
  // import.meta.env는 vite가 빌드 시 치환하지만 vitest에서는 stubEnv로 바꿔 끼울 수 있다.
  vi.stubEnv("VITE_KAKAO_JS_KEY", value ?? "");
}

beforeEach(() => {
  appended = [];
  resetKakaoSdkForTest();
  delete window.kakao;
  appendSpy = vi.spyOn(document.head, "appendChild").mockImplementation((node) => {
    if (node instanceof HTMLScriptElement) appended.push(node);
    return node;
  });
});

afterEach(() => {
  appendSpy.mockRestore();
  vi.unstubAllEnvs();
});

describe("VITE_KAKAO_JS_KEY가 없을 때", () => {
  it("스크립트를 붙이지 않고 안내만 보여준다 (앱은 계속 동작)", async () => {
    setKey("");

    render(<KakaoMap points={POINTS} ariaLabel="식당 위치" />);

    expect(await screen.findByText(/지도 키가 설정되지 않아/)).toBeInTheDocument();
    // 빈 appkey로 붙으면 카카오가 거부할 뿐 사용자에게 설명할 근거가 남지 않는다.
    expect(appended).toHaveLength(0);
  });

  it("공백만 있는 키도 없는 것으로 본다", async () => {
    setKey("   ");

    render(<KakaoMap points={POINTS} ariaLabel="식당 위치" />);

    expect(await screen.findByText(/지도 키가 설정되지 않아/)).toBeInTheDocument();
    expect(appended).toHaveLength(0);
  });
});

describe("SDK 로드 실패", () => {
  it("네트워크 오류를 조용히 삼키고 안내를 띄운다", async () => {
    setKey("test-js-key");

    render(<KakaoMap points={POINTS} ariaLabel="식당 위치" />);

    await waitFor(() => expect(appended).toHaveLength(1));
    appended[0].onerror?.(new Event("error"));

    expect(await screen.findByText(/지도를 불러오지 못했어요/)).toBeInTheDocument();
  });

  it("스크립트는 200인데 kakao.maps가 없는 경우(앱키 거부)도 안내로 처리한다", async () => {
    setKey("test-js-key");

    render(<KakaoMap points={POINTS} ariaLabel="식당 위치" />);

    await waitFor(() => expect(appended).toHaveLength(1));
    appended[0].onload?.(new Event("load"));

    expect(await screen.findByText(/지도를 불러오지 못했어요/)).toBeInTheDocument();
  });
});

describe("SDK 로드 성공", () => {
  it("좌표가 있는 항목만 마커로 찍고, 없는 항목은 건너뛴다", async () => {
    setKey("test-js-key");
    const sdk = fakeSdk();

    render(
      <KakaoMap
        points={[
          ...POINTS,
          // 좌표 없는 식당 — 마커는 못 찍지만 목록(호출하는 화면)에는 남아야 한다
          { id: 3, name: "좌표없는집", latitude: null, longitude: null },
        ]}
        ariaLabel="식당 위치"
      />,
    );

    await waitFor(() => expect(appended).toHaveLength(1));
    resolveScript(sdk);

    await waitFor(() => expect(sdk.markers).toHaveLength(2));
    expect(screen.queryByText(/지도 키가 설정되지 않아/)).not.toBeInTheDocument();
    // 마커가 둘 이상이면 전부 보이도록 bounds로 맞춘다
    expect(sdk.setBoundsCalls).toBe(1);
  });

  it("컴포넌트가 여러 번 마운트돼도 스크립트는 한 번만 붙는다", async () => {
    setKey("test-js-key");
    const sdk = fakeSdk();

    const first = render(<KakaoMap points={POINTS} ariaLabel="식당 위치" />);
    await waitFor(() => expect(appended).toHaveLength(1));
    resolveScript(sdk);
    await waitFor(() => expect(sdk.markers).toHaveLength(2));
    first.unmount();

    render(<KakaoMap points={POINTS} ariaLabel="추천 식당 위치" />);
    await waitFor(() => expect(sdk.maps).toHaveLength(2));

    expect(appended).toHaveLength(1);
  });

  it("언마운트하면 마커를 지도에서 떼어낸다", async () => {
    setKey("test-js-key");
    const sdk = fakeSdk();

    const view = render(<KakaoMap points={POINTS} ariaLabel="식당 위치" />);
    await waitFor(() => expect(appended).toHaveLength(1));
    resolveScript(sdk);
    await waitFor(() => expect(sdk.markers).toHaveLength(2));

    view.unmount();

    // setMap(null)을 빼먹으면 화면을 오갈 때마다 옛 마커가 지도에 그대로 쌓인다
    expect(sdk.markers.every((m) => m.map === null)).toBe(true);
    expect(sdk.removedListeners).toBe(2);
  });
});

it("찍을 좌표가 하나도 없으면 아무것도 렌더하지 않는다", () => {
  setKey("");

  const { container } = render(
    <KakaoMap points={[{ id: 1, name: "좌표없는집" }]} ariaLabel="식당 위치" />,
  );

  expect(container).toBeEmptyDOMElement();
});

// ---- 카카오 SDK 대역 ----

/** 실제로 호출되는 생성자만 흉내 낸다. 지도 렌더 자체는 jsdom에서 확인할 수 없다. */
function fakeSdk() {
  const state = {
    maps: [] as object[],
    markers: [] as { map: object | null }[],
    setBoundsCalls: 0,
    removedListeners: 0,
  };

  const sdk = {
    load: (callback: () => void) => callback(),
    LatLng: class {
      lat: number;
      lng: number;
      constructor(lat: number, lng: number) {
        this.lat = lat;
        this.lng = lng;
      }
      getLat() { return this.lat; }
      getLng() { return this.lng; }
    },
    LatLngBounds: class {
      extend() {}
    },
    Map: class {
      constructor() { state.maps.push(this); }
      setCenter() {}
      setBounds() { state.setBoundsCalls += 1; }
      setLevel() {}
      relayout() {}
    },
    Marker: class {
      map: object | null = null;
      constructor() { state.markers.push(this); }
      setMap(map: object | null) { this.map = map; }
    },
    InfoWindow: class {
      open() {}
      close() {}
    },
    event: {
      addListener: () => {},
      removeListener: () => { state.removedListeners += 1; },
    },
  };

  return Object.assign(state, { sdk });
}

/** 붙어 있던 스크립트가 성공적으로 로드된 척한다. */
function resolveScript(fake: ReturnType<typeof fakeSdk>) {
  // 실제 SDK도 이렇게 전역에 자기를 심고 나서 onload가 불린다.
  window.kakao = { maps: fake.sdk as unknown as KakaoMaps };
  appended[0].onload?.(new Event("load"));
}
