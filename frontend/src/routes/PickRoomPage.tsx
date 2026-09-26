import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  decidePickRoom,
  choosePickRoomPlace,
  fetchPickRoom,
  participantIdFor,
  replacePickRoomVetoes,
} from "../api/pickRooms";
import { apiErrorMessage } from "../api/http";
import { useAuth } from "../auth/AuthContext";
import type { KakaoPlace } from "../api/places";
import { safeExternalUrl } from "../externalUrl";
import NearbyPlaces from "./NearbyPlaces";
import { chipToggle } from "../a11y/chipToggle";
import "./PickRoomPage.css";

/** 결과를 기다리는 동안만 다시 불러온다. 정해지고 나면 바뀔 것이 없다. */
const POLL_MS = 5000;

/**
 * 여럿이 같이 뽑는 방.
 *
 * <h2>로그인 없이 열리는 화면이다</h2>
 *
 * 링크를 받은 사람은 가입하지 않고 들어와 "이건 빼주세요"를 누르고 결과를 본다. 그래서 이
 * 경로는 {@code ProtectedRoute} 바깥에 있다. 결과 식당을 정하는 동작만 방장 로그인이 필요하다.
 *
 * <h2>왜 각자 빼고 한 번만 뽑는가</h2>
 *
 * 점심 메뉴를 정하는 자리에서 어려운 것은 "무엇을 먹을까"보다 <b>"누가 무엇을 못 먹는가"</b>다.
 * 그래서 참여자는 고르는 게 아니라 <b>빼기만</b> 한다. 그리고 결과는 한 번만 정해진다 —
 * 다시 뽑기가 열려 있으면 마음에 안 드는 사람이 계속 굴려 결국 아무도 동의하지 않은 결과가 남는다.
 */
export default function PickRoomPage() {
  const { code = "" } = useParams();
  const { isLoading: authLoading } = useAuth();
  const queryClient = useQueryClient();
  // 방마다 하나씩 만든다 — 근거는 participantIdFor.
  const participant = useMemo(() => participantIdFor(code), [code]);

  const roomQuery = useQuery({
    queryKey: ["pick-room", code, participant],
    queryFn: () => fetchPickRoom(code, participant),
    enabled: !authLoading,
    // 결과가 나오기 전까지만 폴링한다. 다른 사람의 제외가 화면에 들어와야 "같이" 정하는 느낌이 된다.
    refetchInterval: (query) => query.state.data?.decision?.place ? false
      : query.state.data?.decision ? 15000 : POLL_MS,
    retry: false,
  });

  const room = roomQuery.data;
  // 낙관적 표시: 누른 즉시 칩이 반응해야 한다. 서버 응답이 오면 그 값으로 덮는다.
  const [pending, setPending] = useState<Set<number> | null>(null);
  const decidedRef = useRef<HTMLDivElement>(null);

  const vetoed = useMemo(() => {
    if (pending) return pending;
    return new Set((room?.menus ?? []).filter((menu) => menu.vetoedByMe).map((menu) => menu.id));
  }, [pending, room]);

  const saveVetoes = useMutation({
    mutationFn: (next: Set<number>) => replacePickRoomVetoes(code, participant, [...next]),
    onSuccess: (updated) => {
      setPending(null);
      queryClient.setQueryData(["pick-room", code, participant], updated);
    },
    // 실패하면 낙관적 표시를 거둔다 — 서버에 없는 제외가 화면에만 남아 있으면 안 된다.
    onError: () => setPending(null),
  });

  const decide = useMutation({
    mutationFn: () => decidePickRoom(code),
    onSuccess: (updated) => {
      queryClient.setQueryData(["pick-room", code, participant], updated);
      // decide는 공개 API라 호스트 여부를 모른다. 인증 정보가 있는 GET으로 바로 다시 읽는다.
      void queryClient.invalidateQueries({ queryKey: ["pick-room", code, participant] });
    },
  });

  // 결과가 나오면 그 카드로 초점을 옮긴다. 다른 사람이 누른 결과가 폴링으로 들어올 수도 있어,
  // 화면만 바뀌고 스크린리더에는 아무 일도 없는 상태가 되기 쉽다.
  useEffect(() => {
    if (room?.decision) decidedRef.current?.focus();
  }, [room?.decision]);

  if (roomQuery.isPending) {
    return <div className="page"><p className="settings-desc">방을 여는 중…</p></div>;
  }

  if (roomQuery.isError || !room) {
    return (
      <div className="page">
        <header className="page-header"><h1>같이 뽑기</h1></header>
        <section className="card">
          <p className="error" role="alert">{apiErrorMessage(roomQuery.error)}</p>
          {/* 만료와 오타를 구분해 주지 않는다(서버도 같은 404다). 할 일은 어느 쪽이든 같다. */}
          <p className="card-muted-hint">
            링크가 만료됐거나 잘못된 주소예요. 방을 만든 분께 새 링크를 받아주세요.
          </p>
          <Link to="/pick">내 메뉴로 뽑으러 가기 →</Link>
        </section>
      </div>
    );
  }

  const toggle = (menuId: number) => {
    const next = new Set(vetoed);
    if (next.has(menuId)) next.delete(menuId);
    else next.add(menuId);
    setPending(next);
    saveVetoes.mutate(next);
  };

  const remaining = room.menus.filter((menu) => menu.vetoedBy === 0).length;

  return (
    <div className="page pick-room">
      <header className="page-header">
        <h1>같이 뽑기</h1>
      </header>

      {room.decision ? (
        <section className="card pick-room-result">
          <div ref={decidedRef} tabIndex={-1} role="status">
            <p className="settings-desc">오늘의 메뉴는</p>
            <strong className="pick-room-result-name">{room.decision.menuName}</strong>
          </div>
          <p className="card-muted-hint">
            한 번 정해진 결과는 바뀌지 않아요. 다시 정하려면 새 방을 만들어 주세요.
          </p>
          {room.decision.place ? (
            <p className="card-muted-hint" role="status">
              먹으러 갈 곳: <strong>{room.decision.place.name}</strong>{" "}
              {safeExternalUrl(room.decision.place.url) && (
                <a href={safeExternalUrl(room.decision.place.url)!} target="_blank" rel="noopener noreferrer">
                  카카오맵에서 보기<span className="sr-only"> (새 창)</span>
                </a>
              )}
            </p>
          ) : room.canChoosePlace ? (
            <NearbyPlaces
              menuName={room.decision.menuName}
              roomMode
              choosePlace={async (place: KakaoPlace) => {
                const updated = await choosePickRoomPlace(code, place);
                queryClient.setQueryData(["pick-room", code, participant], updated);
                return {
                  restaurantId: 0,
                  restaurantName: updated.decision!.place!.name,
                  restaurantCreated: false,
                  linkCreated: false,
                };
              }}
            />
          ) : (
            <p className="card-muted-hint">방을 만든 사람이 근처 식당을 고르면 여기에 함께 보여요.</p>
          )}
          <Link to="/pick">내 메뉴로 뽑으러 가기 →</Link>
        </section>
      ) : (
        <>
          <section className="card">
            <p className="settings-desc">
              못 먹거나 오늘은 피하고 싶은 메뉴를 눌러서 빼주세요. 지금 <strong>{room.participantCount}명</strong>이
              참여했고, 아무도 빼지 않은 메뉴가 <strong>{remaining}개</strong> 남았어요.
            </p>
            {saveVetoes.isError && (
              <p className="error" role="alert">{apiErrorMessage(saveVetoes.error)}</p>
            )}
            <ul className="chip-row" role="list">
              {room.menus.map((menu) => (
                <li key={menu.id}>
                  <button
                    type="button"
                    {...chipToggle(vetoed.has(menu.id))}
                    aria-label={
                      `${menu.name}${menu.vetoedBy > 0 ? `, ${menu.vetoedBy}명이 뺌` : ""}` +
                      `${vetoed.has(menu.id) ? ", 내가 뺀 메뉴" : ""}`
                    }
                    onClick={() => toggle(menu.id)}
                  >
                    {menu.name}
                    {menu.vetoedBy > 0 && (
                      <span className="pick-room-veto-count" aria-hidden="true"> ✕{menu.vetoedBy}</span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          </section>

          <section className="card">
            {decide.isError && (
              <p className="error" role="alert">{apiErrorMessage(decide.error)}</p>
            )}
            <button
              type="button"
              className="pick-button"
              aria-busy={decide.isPending}
              aria-disabled={decide.isPending || undefined}
              onClick={() => {
                // aria-disabled는 표시일 뿐이다. 두 사람이 동시에 눌러도 서버가 먼저 정해진
                // 결과를 지키지만(멱등), 같은 사람이 두 번 보내는 것은 여기서 막는다.
                if (decide.isPending) return;
                decide.mutate();
              }}
            >
              {decide.isPending ? "뽑는 중…" : "🎲 남은 메뉴 중에서 뽑기"}
            </button>
            <p className="card-muted-hint">
              누구나 누를 수 있고, <strong>한 번만</strong> 뽑혀요. 다 모인 뒤에 눌러주세요.
            </p>
          </section>
        </>
      )}
    </div>
  );
}
