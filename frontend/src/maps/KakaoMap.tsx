import { useEffect, useMemo, useRef, useState } from "react";
import {
  loadKakaoMaps,
  MISSING_KEY,
  type KakaoInfoWindow,
  type KakaoMapInstance,
  type KakaoMaps,
} from "./kakaoSdk";
import "./KakaoMap.css";

export interface KakaoMapPoint {
  id: string | number;
  name: string;
  address?: string | null;
  // 좌표가 없는 항목도 그대로 받는다. 호출하는 화면이 목록에서 걸러내야 한다면
  // "지도에 못 찍는 식당은 목록에서도 사라지는" 결과가 되므로, 거르는 일은 여기서 한다.
  latitude?: number | null;
  longitude?: number | null;
}

/** 지도를 못 띄우는 이유는 사용자에게 각각 다른 말로 알려야 해서 상태를 나눈다. */
type Status = "loading" | "ready" | "missing-key" | "error";

interface Props {
  points: KakaoMapPoint[];
  /** 마커가 하나뿐일 때의 확대 수준. 여러 개면 bounds가 이 값을 덮어쓴다. */
  level?: number;
  ariaLabel: string;
}

/**
 * 카카오맵에 마커를 찍는 재사용 컴포넌트.
 *
 * <p>지도는 이 앱의 부가 기능이다. 그래서 <b>어떤 실패도 화면 전체를 막지 않는다</b> —
 * 키가 없든(운영 서버에 아직 VITE_KAKAO_JS_KEY가 없다) SDK 로드가 거부되든
 * 지도 자리에 안내 한 줄만 남고 목록·검색·저장은 그대로 동작한다.
 * 키 자체에 대한 설명은 kakaoSdk.ts 참고.
 */
export default function KakaoMap({ points, level = 5, ariaLabel }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<KakaoMapInstance | null>(null);
  // 열려 있는 말풍선은 하나뿐이어야 한다 — 마커를 연달아 누르면 말풍선이 겹쳐 쌓인다.
  const openRef = useRef<KakaoInfoWindow | null>(null);
  const [maps, setMaps] = useState<KakaoMaps | null>(null);
  const [status, setStatus] = useState<Status>("loading");

  // 좌표가 없거나 숫자가 아닌 항목은 마커를 건너뛴다. NaN을 LatLng에 넣으면 예외가 아니라
  // "지도가 엉뚱한 곳으로 튀는" 형태로 나타나 원인을 찾기 어렵다.
  const plotted = useMemo(
    () =>
      points.filter(
        (p): p is KakaoMapPoint & { latitude: number; longitude: number } =>
          Number.isFinite(p.latitude) && Number.isFinite(p.longitude),
      ),
    [points],
  );

  // 컨테이너는 plotted가 빌 동안 렌더되지 않는다(아래 early return). 그래서 지도 생성 효과의
  // 의존성에 "찍을 게 생겼는지"를 반드시 포함해야 한다 — 이게 빠지면 SDK가 목록 API보다 먼저
  // 준비됐을 때 첫 실행이 container === null로 빠져나가고, 뒤늦게 좌표가 도착해도 maps·level이
  // 그대로라 효과가 다시 돌지 않아 지도가 영영 만들어지지 않는다(빈 회색 박스만 남는다).
  // /pick에서 지도를 한 번 띄운 뒤 /restaurants로 이동하면 SDK가 모듈 캐시에서 즉시 resolve되어
  // 확정적으로 재현된다.
  const hasPoints = plotted.length > 0;

  useEffect(() => {
    // 언마운트 뒤에 도착하는 로드 결과로 setState하지 않도록 세대를 표시한다.
    let alive = true;
    loadKakaoMaps().then(
      (sdk) => {
        if (!alive) return;
        setMaps(sdk);
        setStatus("ready");
      },
      (error: Error) => {
        if (!alive) return;
        setStatus(error.message === MISSING_KEY ? "missing-key" : "error");
      },
    );
    return () => {
      alive = false;
    };
  }, []);

  // 지도 인스턴스는 한 번만 만든다. 마커가 바뀔 때마다 다시 만들면 사용자가 옮겨 둔
  // 위치·확대 수준이 목록 갱신마다 초기화된다.
  useEffect(() => {
    const container = containerRef.current;
    if (!maps || !container) return;

    mapRef.current = new maps.Map(container, {
      center: new maps.LatLng(DEFAULT_CENTER.lat, DEFAULT_CENTER.lng),
      level,
    });

    return () => {
      mapRef.current = null;
      // SDK에 파괴 API가 없다. 컨테이너를 비워야 지도가 만든 DOM과 그에 매달린
      // 리스너가 함께 떨어진다. 이 div에는 React가 자식을 렌더하지 않으므로 안전하다.
      container.innerHTML = "";
    };
    // level은 초기 확대 수준일 뿐이라 바뀌어도 지도를 다시 만들지 않는다(아래 효과가 반영한다).
  }, [maps, level, hasPoints]);

  useEffect(() => {
    const map = mapRef.current;
    if (!maps || !map || plotted.length === 0) return;

    const bounds = new maps.LatLngBounds();
    const created = plotted.map((point) => {
      const position = new maps.LatLng(point.latitude, point.longitude);
      bounds.extend(position);
      const marker = new maps.Marker({ position, title: point.name });
      marker.setMap(map);

      // 말풍선 내용은 DOM으로 만든다. 문자열 HTML로 넘기면 식당 이름·주소가 그대로
      // 파싱되어 카카오에서 가져온 상호명 안의 태그가 실행될 수 있다.
      const infoWindow = new maps.InfoWindow({ content: infoContent(point), removable: true });
      const handler = () => {
        openRef.current?.close();
        infoWindow.open(map, marker);
        openRef.current = infoWindow;
      };
      maps.event.addListener(marker, "click", handler);
      return { marker, infoWindow, handler };
    });

    // 마커가 하나면 bounds가 점 하나라 최대 배율까지 당겨진다 — 주변이 안 보여
    // 어디인지 알 수 없으므로 이때만 중심 이동으로 처리한다.
    if (plotted.length === 1) {
      map.setCenter(new maps.LatLng(plotted[0].latitude, plotted[0].longitude));
      map.setLevel(level);
    } else {
      map.setBounds(bounds);
    }

    return () => {
      openRef.current?.close();
      openRef.current = null;
      created.forEach(({ marker, infoWindow, handler }) => {
        maps.event.removeListener(marker, "click", handler);
        infoWindow.close();
        // setMap(null)을 빼먹으면 목록이 갱신될 때마다 옛 마커가 지도에 그대로 쌓인다.
        marker.setMap(null);
      });
    };
  }, [maps, plotted, level]);

  // 찍을 좌표가 하나도 없으면 지도를 띄우지 않는다. 빈 지도는 정보가 없을 뿐 아니라,
  // 키가 없을 때 뜨는 안내까지 같이 보여줘 "저장한 식당이 없다"와 뒤섞인다.
  if (plotted.length === 0) return null;

  return (
    // aria-live="off": 픽 결과는 aria-live 영역 안에 있는데, SDK가 지도를 그리면서
    // 노드를 수백 개 만든다. 그대로 두면 스크린리더가 그 변화를 전부 읽어 정작 중요한
    // "무엇이 뽑혔는지"가 묻힌다. 지도 안쪽만 알림에서 빼낸다.
    <div className="kakao-map" aria-live="off">
      <div
        ref={containerRef}
        className={status === "ready" ? "kakao-map-canvas" : "kakao-map-canvas is-hidden"}
        role="application"
        aria-label={ariaLabel}
      />
      {status !== "ready" && <p className="kakao-map-notice">{NOTICE[status]}</p>}
    </div>
  );
}

/** 좌표가 하나도 없을 때의 초기 중심(서울시청). 곧 bounds나 setCenter가 덮어쓴다. */
const DEFAULT_CENTER = { lat: 37.5665, lng: 126.978 };

const NOTICE: Record<Exclude<Status, "ready">, string> = {
  loading: "지도를 불러오는 중…",
  // 키가 없는 건 사용자가 뭘 잘못해서가 아니다. 목록은 멀쩡히 보인다는 걸 같이 알린다.
  "missing-key": "지도 키가 설정되지 않아 지도를 표시할 수 없어요. 아래 목록은 그대로 이용할 수 있습니다.",
  error: "지도를 불러오지 못했어요. 아래 목록은 그대로 이용할 수 있습니다.",
};

function infoContent(point: KakaoMapPoint) {
  const wrapper = document.createElement("div");
  wrapper.className = "kakao-map-info";

  const name = document.createElement("strong");
  name.textContent = point.name;
  wrapper.appendChild(name);

  if (point.address) {
    const address = document.createElement("span");
    address.textContent = point.address;
    wrapper.appendChild(address);
  }
  return wrapper;
}
