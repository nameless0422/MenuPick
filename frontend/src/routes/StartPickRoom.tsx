import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation } from "@tanstack/react-query";
import { createPickRoom, type PickRoom } from "../api/pickRooms";
import { apiErrorMessage } from "../api/http";
import { shareTextAndUrl, type ShareOutcome } from "./sharePick";

/**
 * 픽 화면에서 "여럿이 같이 뽑기"를 시작한다.
 *
 * <h2>방을 먼저 만들고 링크를 준다</h2>
 *
 * 링크를 공유하는 순간이 이 기능의 전부라, 버튼 한 번에 방이 만들어지고 바로 공유까지 이어져야
 * 한다. 공유는 픽 결과 공유와 같은 경로를 쓴다 — 공유 시트 → 클립보드 → 직접 복사
 * ({@code sharePick.ts}). **이 서버는 자체 서명 인증서라 자동 복사가 막히는 일이 실제로 있다.**
 *
 * <h2>지금 고른 카테고리를 그대로 넘긴다</h2>
 *
 * "오늘은 한식 중에서만" 같은 자리가 실제로 있다. 픽 화면에서 이미 고른 조건이 있으면 그대로
 * 방에 싣는다 — 같은 화면에서 조건을 두 번 고르게 하지 않는다. 태그·거리는 넘기지 않는다:
 * 태그는 내 어휘라 남에게 의미가 없고, 거리는 참여자마다 현재 위치가 다르다.
 */
export default function StartPickRoom({ categories }: { categories: string[] }) {
  const [room, setRoom] = useState<PickRoom | null>(null);
  const [shared, setShared] = useState<ShareOutcome | null>(null);
  const createdRef = useRef<HTMLDivElement>(null);

  const create = useMutation({
    mutationFn: () => createPickRoom(categories),
    onSuccess: setRoom,
  });

  // 방이 만들어지면 그 카드로 초점을 옮긴다 — 방금 누른 버튼이 사라지는 자리다.
  useEffect(() => {
    if (room) createdRef.current?.focus();
  }, [room]);

  if (room) {
    const url = `${window.location.origin}/rooms/${room.code}`;
    return (
      <section className="card pick-room-invite" aria-label="같이 뽑기 방">
        <div ref={createdRef} tabIndex={-1} role="status">
          <p className="settings-desc">
            방을 만들었어요. 링크를 보내면 상대는 <strong>가입 없이</strong> 들어와 못 먹는 메뉴를
            뺄 수 있어요. 6시간 뒤에 닫혀요.
          </p>
        </div>

        {/* 공유가 막히는 환경이 실제로 있어서 주소를 항상 보여준다 — 최소한 눈으로 읽어 옮길 수 있다. */}
        <input readOnly value={url} aria-label="방 링크" onFocus={(e) => e.currentTarget.select()} />

        <div className="card-actions">
          <button
            type="button"
            onClick={() => {
              void shareTextAndUrl(
                `같이 점심 메뉴 정해요! 못 먹는 메뉴를 빼주세요 (${room.menus.length}개 중에서)`,
                url,
              ).then(setShared);
            }}
          >
            📤 링크 공유하기
          </button>
          <Link to={`/rooms/${room.code}`}>방 열기 →</Link>
        </div>

        {shared?.kind === "copied" && (
          <p className="card-muted-hint" role="status">링크를 복사했어요. 붙여넣기로 보내세요.</p>
        )}
        {shared?.kind === "manual" && (
          <p className="card-muted-hint" role="status">
            자동 복사가 막혀 있어요. 위 주소를 직접 복사해 주세요.
          </p>
        )}
      </section>
    );
  }

  return (
    <p className="card-muted-hint">
      같이 먹을 사람이 있다면{" "}
      <button
        type="button"
        className="auth-inline-button"
        aria-busy={create.isPending}
        aria-disabled={create.isPending || undefined}
        onClick={() => {
          if (create.isPending) return;
          create.mutate();
        }}
      >
        {create.isPending ? "방 만드는 중…" : "여럿이 같이 뽑기"}
      </button>
      {categories.length > 0 && <> (고른 카테고리 {categories.length}개로)</>}
      . 링크를 받은 사람은 가입 없이 참여해요.
      {create.isError && <span className="error" role="alert"> {apiErrorMessage(create.error)}</span>}
    </p>
  );
}
