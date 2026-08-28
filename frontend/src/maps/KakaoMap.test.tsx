import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import KakaoMap, { type KakaoMapPoint } from "./KakaoMap";
import { resetKakaoSdkForTest, type KakaoMaps } from "./kakaoSdk";

/**
 * 이 파일이 지키는 계약은 하나다: <b>지도는 실패해도 앱을 깨뜨리지 않는다.</b>
 *
 * <p>키가 빠진 채 배포되는 일은 실제로 일어난다 — #82가 web 빌드를 서버에서 CI로 옮기면서
 * 값의 출처가 서버 .env에서 저장소 시크릿으로 바뀌었는데 시크릿이 없어, 빈 키로 빌드된
 * 번들이 엿새 동안 운영에 떠 있었다(빌드도 배포도 성공한다 — 지도만 안 뜬다).
 * 그 상태에서 식당·픽 화면이 통째로 죽으면 지도를 얹은 것 자체가 손해다. 그래서
 * "키 없음"과 "로드 실패"를 각각 렌더까지 태워 확인한다.
 *
 * <p>이 테스트는 키가 <b>없을 때 앱이 버티는지</b>만 본다. 키가 실제로 들어가는지는
 * 여기서 알 수 없다(빌드 타임 치환이라 번들을 봐야 한다) — 그쪽은 CI의
 * "Verify frontend build secrets" 스텝이 막는다.
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

describe("좌표가 SDK보다 늦게 도착할 때", () => {
  // 목록 API가 SDK 로드보다 느린 흔한 순서다. 특히 /pick에서 지도를 한 번 띄운 뒤
  // /restaurants로 이동하면 SDK가 모듈 캐시에서 즉시 resolve되어 확정적으로 이 순서가 된다.
  // 지도 생성 효과가 "찍을 게 생겼는지"를 의존성에 넣지 않으면, 컨테이너가 없는 동안 한 번
  // 빠져나간 뒤 다시 돌지 않아 사용자에게는 빈 박스만 남는다.
  it("뒤늦게 좌표가 들어와도 지도를 만든다", async () => {
    setKey("test-js-key");
    const sdk = fakeSdk();

    const view = render(<KakaoMap points={[]} ariaLabel="식당 위치" />);
    await waitFor(() => expect(appended).toHaveLength(1));

    // act로 감싸 로드 결과가 상태에 반영될 때까지 흘려보낸다. 이걸 안 하면 좌표를 넣는
    // 시점에 maps가 아직 null이라, 고치기 전 코드로도 통과해 버려 회귀를 못 잡는다.
    await act(async () => {
      resolveScript(sdk);
    });
    expect(sdk.maps).toHaveLength(0);

    view.rerender(<KakaoMap points={POINTS} ariaLabel="식당 위치" />);

    await waitFor(() => expect(sdk.maps).toHaveLength(1));
    expect(sdk.markers).toHaveLength(2);
  });

  it("좌표가 비었다가 다시 들어오면 지도를 새로 만든다", async () => {
    setKey("test-js-key");
    const sdk = fakeSdk();

    const view = render(<KakaoMap points={POINTS} ariaLabel="식당 위치" />);
    await waitFor(() => expect(appended).toHaveLength(1));
    resolveScript(sdk);
    await waitFor(() => expect(sdk.maps).toHaveLength(1));

    // 필터를 좁혀 결과가 0건이 된 상태. 컨테이너가 사라지므로 지도도 함께 정리돼야 한다.
    view.rerender(<KakaoMap points={[]} ariaLabel="식당 위치" />);
    await waitFor(() => expect(sdk.markers.every((m) => m.map === null)).toBe(true));

    view.rerender(<KakaoMap points={POINTS} ariaLabel="식당 위치" />);

    await waitFor(() => expect(sdk.maps).toHaveLength(2));
  });
});

it("찍을 좌표가 하나도 없으면 아무것도 렌더하지 않는다", () => {
  setKey("");

  const { container } = render(
    <KakaoMap points={[{ id: 1, name: "좌표없는집" }]} ariaLabel="식당 위치" />,
  );

  expect(container).toBeEmptyDOMElement();
});

describe("스크린리더에 드러나는 모양", () => {
  it('캔버스는 role="img"다 — 키보드 조작이 없는데 브라우즈 모드를 끄면 안 된다', async () => {
    setKey("test-js-key");
    const sdk = fakeSdk();

    render(<KakaoMap points={POINTS} ariaLabel="저장한 식당 위치" />);
    await waitFor(() => expect(appended).toHaveLength(1));
    resolveScript(sdk);
    await waitFor(() => expect(sdk.maps).toHaveLength(1));

    // role="application"이면 NVDA/JAWS가 가상 커서를 끈다. 이 컴포넌트에는 키 핸들러가
    // 하나도 없으므로, 그 순간 사용자는 조작 수단 없이 탐색 수단만 잃는다.
    const canvas = screen.getByRole("img", { name: "저장한 식당 위치" });
    expect(canvas).not.toHaveAttribute("role", "application");
    // SDK가 만드는 수백 개 노드는 여전히 무음이어야 한다 — 픽 결과 낭독을 덮어쓴다.
    expect(canvas).toHaveAttribute("aria-live", "off");
  });

  it("지도를 못 띄웠다는 안내가 통지된다", async () => {
    setKey("");

    render(<KakaoMap points={POINTS} ariaLabel="저장한 식당 위치" />);

    const notice = await screen.findByRole("status");
    expect(notice).toHaveTextContent(/지도 키가 설정되지 않아/);
  });

  it("안내가 조상의 aria-live=\"off\"에 갇히지 않는다", async () => {
    setKey("test-js-key");

    render(<KakaoMap points={POINTS} ariaLabel="저장한 식당 위치" />);
    await waitFor(() => expect(appended).toHaveLength(1));
    appended[0].onerror?.(new Event("error"));

    const notice = await screen.findByText(/지도를 불러오지 못했어요/);
    // aria-live는 상속되고 자손의 off가 조상의 polite를 이긴다. 래퍼에 off가 걸려 있으면
    // role="status"를 붙여도 이 안내는 영영 읽히지 않는다.
    for (let el = notice.parentElement; el; el = el.parentElement) {
      expect(el.getAttribute("aria-live")).not.toBe("off");
    }
  });
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
