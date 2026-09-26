import { useEffect, useId, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { searchNearbyPlaces, type KakaoPlace } from "../api/places";
import { choosePickPlace, type PlaceChoiceResult } from "../api/history";
import { apiErrorMessage } from "../api/http";
import { safeExternalUrl } from "../externalUrl";
import { formatDistance } from "./formatDistance";

type Location =
  | { status: "idle" }
  | { status: "locating" }
  | { status: "ready"; latitude: number; longitude: number }
  | { status: "unavailable"; message: string };

/**
 * 뽑은 메뉴를 근처 어디서 먹을지 — 픽 결과 카드 안의 주변 식당 찾기.
 *
 * <h2>왜 필요한가</h2>
 *
 * 2026-09-16 운영 기준 저장된 식당이 1곳이었다. 식당 화면에서 검색해 저장하고 메뉴에 연결하는
 * 두 단계를 아무도 밟지 않는다. 그런데 사용자가 "어디서 먹지"를 고민하는 순간은 바로 뽑은
 * 직후이고, 그때 고른 가게가 저장·연결까지 되면 다음부터는 거리로 뽑기와 지도가 저절로 채워진다.
 *
 * <h2>누르기 전에는 위치를 묻지 않는다</h2>
 *
 * 결과가 나올 때마다 위치 권한을 요청하면, 연결 식당만 보려던 사람에게도 권한 창이 뜬다.
 * 버튼을 누른 사람에게만 묻는다. 거리 필터를 켠 채 뽑았더라도 다시 받는다 — 그 좌표는 픽을
 * 요청할 때의 것이고, 이 카드는 그 뒤로 몇 분 떠 있을 수 있다.
 *
 * <h2>고르면 무엇이 되는가</h2>
 *
 * 서버가 식당 저장·메뉴 연결·이 픽에 식당 기록을 한 번에 한다. **방문 처리는 하지 않는다** —
 * 아직 가기 전이고, 먹었는지는 다음에 픽 화면에서 묻는다.
 */
export default function NearbyPlaces({
  historyId,
  menuId,
  menuName,
  choosePlace,
  roomMode = false,
}: {
  historyId?: number;
  menuId?: number;
  menuName: string;
  choosePlace?: (place: KakaoPlace) => Promise<PlaceChoiceResult>;
  roomMode?: boolean;
}) {
  const headingId = useId();
  const queryClient = useQueryClient();
  const [location, setLocation] = useState<Location>({ status: "idle" });
  const [chosen, setChosen] = useState<PlaceChoiceResult | null>(null);
  const doneRef = useRef<HTMLParagraphElement>(null);
  const listRef = useRef<HTMLUListElement>(null);

  // 위치 요청은 취소할 수 없다. 카드가 사라진 뒤(다시 돌리기) 도착한 콜백이 상태를 건드리지 않게 한다.
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  const position = location.status === "ready" ? location : null;
  const searchQuery = useQuery({
    // 좌표는 소수 넷째 자리(약 10m)까지만 키에 넣는다. 그보다 잘게 넣으면 같은 자리에서 다시
    // 눌러도 GPS 흔들림 때문에 매번 카카오 호출이 새로 나간다(사용자당 분당 30회 제한).
    queryKey: [
      "nearby-places",
      menuName,
      position ? position.latitude.toFixed(4) : null,
      position ? position.longitude.toFixed(4) : null,
    ],
    queryFn: () => searchNearbyPlaces(menuName, position!),
    enabled: position !== null,
  });

  const chooseMutation = useMutation({
    mutationFn: (place: KakaoPlace) => choosePlace
      ? choosePlace(place)
      : choosePickPlace(historyId!, place),
    onSuccess: (result) => {
      setChosen(result);
      void queryClient.invalidateQueries({ queryKey: ["restaurants"] });
      if (menuId != null) void queryClient.invalidateQueries({ queryKey: ["menu-restaurants", menuId] });
      void queryClient.invalidateQueries({ queryKey: ["history"] });
    },
  });

  // 결과가 뜨면 목록으로, 고르고 나면 결과 문장으로 초점을 옮긴다. 둘 다 방금 누른 버튼이
  // 사라지는 순간이라 가만두면 초점이 <body>로 떨어진다.
  useEffect(() => {
    if (chosen) doneRef.current?.focus();
  }, [chosen]);
  const places = searchQuery.data?.documents ?? [];
  useEffect(() => {
    if (searchQuery.isSuccess) listRef.current?.focus();
  }, [searchQuery.isSuccess]);

  const locate = () => {
    if (!("geolocation" in navigator)) {
      setLocation({
        status: "unavailable",
        message: "이 브라우저에서는 위치를 쓸 수 없어 주변 식당을 찾을 수 없어요.",
      });
      return;
    }
    setLocation({ status: "locating" });
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        if (!alive.current) return;
        setLocation({ status: "ready", latitude: pos.coords.latitude, longitude: pos.coords.longitude });
      },
      () => {
        if (!alive.current) return;
        setLocation({
          status: "unavailable",
          message: "위치를 가져오지 못했어요. 브라우저의 위치 권한을 확인한 뒤 다시 눌러 주세요.",
        });
      },
      { timeout: 10_000 },
    );
  };

  if (chosen) {
    return (
      <section className="nearby-places" aria-labelledby={headingId}>
        <h3 id={headingId} className="sr-only">근처 식당</h3>
        <p ref={doneRef} tabIndex={-1} role="status" className="nearby-places-done">
          <strong>{chosen.restaurantName}</strong>(으)로 정했어요.{" "}
          {roomMode
            ? "방에 있는 모두에게 이 식당을 보여줘요."
            : <>{chosen.linkCreated
              ? `${menuName}에 연결해 뒀으니 다음엔 거리로 뽑을 때도 후보가 돼요.`
              : `이미 ${menuName}에 연결된 식당이에요.`}{" "}
              다녀오시면 다음에 먹었는지 여쭤볼게요.</>}
        </p>
      </section>
    );
  }

  const busy = location.status === "locating" || searchQuery.isFetching;

  return (
    <section className="nearby-places" aria-labelledby={headingId}>
      <h3 id={headingId}>어디서 먹을까요?</h3>

      {!searchQuery.isSuccess && (
        <button
          type="button"
          aria-busy={busy}
          aria-disabled={busy || undefined}
          onClick={() => {
            // aria-disabled는 표시일 뿐이다. 위치 요청이 겹치지 않게 여기서 막는다.
            if (busy) return;
            if (searchQuery.isError) {
              void searchQuery.refetch();
              return;
            }
            locate();
          }}
        >
          {location.status === "locating"
            ? "위치 확인 중…"
            : searchQuery.isFetching
              ? "찾는 중…"
              : `📍 근처 ${menuName} 식당 찾기`}
        </button>
      )}

      {location.status === "unavailable" && (
        <p className="error" role="alert">{location.message}</p>
      )}
      {searchQuery.isError && (
        <p className="error" role="alert">{apiErrorMessage(searchQuery.error)}</p>
      )}

      {searchQuery.isSuccess && places.length === 0 && (
        <p className="card-muted-hint" role="status">
          반경 2km 안에서 ‘{menuName}’ 식당을 찾지 못했어요.
        </p>
      )}

      {places.length > 0 && (
        <>
          {/* 몇 곳인지부터 알린다. 목록으로 초점이 옮겨 가면 이 이름이 먼저 읽힌다. */}
          <ul
            ref={listRef}
            tabIndex={-1}
            className="pick-restaurants"
            role="list"
            aria-label={`근처 ${menuName} 식당 ${places.length}곳, 가까운 순`}
          >
            {places.map((place) => {
              const link = safeExternalUrl(place.place_url);
              const distance = Number(place.distance);
              return (
                <li key={place.id}>
                  <strong>{place.place_name}</strong>
                  {place.distance && Number.isFinite(distance) && (
                    <span className="chip">{formatDistance(distance)}</span>
                  )}
                  {(place.road_address_name || place.address_name) && (
                    <span className="pick-restaurant-address">
                      {place.road_address_name || place.address_name}
                    </span>
                  )}
                  {link && (
                    <a href={link} target="_blank" rel="noopener noreferrer">
                      카카오맵<span className="sr-only"> ({place.place_name}, 새 창)</span>
                    </a>
                  )}
                  <button
                    type="button"
                    aria-label={`${place.place_name}에서 먹을게요`}
                    aria-busy={chooseMutation.isPending && chooseMutation.variables?.id === place.id}
                    aria-disabled={chooseMutation.isPending || undefined}
                    onClick={() => {
                      // 두 번 눌러도 서버는 같은 식당·연결을 새로 만들지 않지만, 요청이 겹치면
                      // 유니크 제약 충돌로 한쪽이 실패해 오류가 보인다. 여기서 막는다.
                      if (chooseMutation.isPending) return;
                      chooseMutation.mutate(place);
                    }}
                  >
                    여기서 먹을게요
                  </button>
                </li>
              );
            })}
          </ul>
          <p className="card-muted-hint">장소 정보: 카카오</p>
        </>
      )}

      {chooseMutation.isError && (
        <p className="error" role="alert">{apiErrorMessage(chooseMutation.error)}</p>
      )}
    </section>
  );
}
