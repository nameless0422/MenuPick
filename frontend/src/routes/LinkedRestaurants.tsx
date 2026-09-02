import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  deleteMenuRestaurant,
  fetchMenuRestaurants,
  updateMenuRestaurant,
  type MenuRestaurantDetail,
} from "../api/menuRestaurants";
import { apiErrorMessage as errorMessage } from "../api/http";
import { starToggle } from "../a11y/starToggle";

/**
 * 메뉴에 연결된 식당 목록과 그 연결의 관리(별점·메모 수정, 연결 해제).
 *
 * <h2>왜 이 화면이 없었나</h2>
 *
 * <p>백엔드에는 수정·해제 API가 처음부터 있었고 테스트도 있었다. 그런데 프론트에는
 * <b>연결을 보여주는 자리 자체가 없었다</b> — 식당 화면에서 메뉴에 붙이는 것만 가능했다.
 * 그래서 사용자는 한번 붙인 식당을 뗄 수도, 별점을 고칠 수도 없었다. 기획 문서는 이 기능을
 * "구현 완료"로 적고 있었는데, API가 끝난 것을 기능이 끝난 것으로 표시한 결과였다.
 *
 * <h2>왜 메뉴 수정 폼 안인가</h2>
 *
 * <p>연결은 메뉴에 딸린 것이고(API 경로부터 {@code /menus/{menuId}/restaurants}) 사용자가
 * 메뉴를 손보러 오는 자리가 이미 이 폼이다. 목록 카드마다 연결을 펼치게 하면 메뉴 수만큼
 * 조회가 나가지만(N+1), 폼은 열릴 때 한 번만 조회한다.
 */
export default function LinkedRestaurants({ menuId }: { menuId: number }) {
  const queryClient = useQueryClient();
  const linksQuery = useQuery({
    queryKey: ["menu-restaurants", menuId],
    queryFn: () => fetchMenuRestaurants(menuId),
  });
  const links = linksQuery.data?.menuRestaurants ?? [];

  // 어떤 연결을 편집 중인지. 한 번에 하나만 연다 — 여러 줄을 동시에 편집하면
  // 저장하지 않은 값이 어디에 남아 있는지 사용자가 추적할 수 없다.
  const [editing, setEditing] = useState<number | null>(null);

  // 해제하면 그 줄이 사라져 방금 누른 버튼과 함께 초점이 <body>로 떨어진다. 남는 줄이 있으면
  // 목록으로, 마지막 하나였으면 목록도 사라지므로 이 묶음(fieldset)으로 초점을 옮긴다.
  const groupRef = useRef<HTMLFieldSetElement>(null);
  const listRef = useRef<HTMLUListElement>(null);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["menu-restaurants", menuId] });

  const unlinkMutation = useMutation({
    mutationFn: ({ restaurantId }: { restaurantId: number; name: string }) =>
      deleteMenuRestaurant(menuId, restaurantId),
    onSuccess: () => {
      (links.length <= 1 ? groupRef : listRef).current?.focus();
      invalidate();
    },
  });

  return (
    // tabIndex={-1}이 있어야 focus()가 먹는다. Tab 순서에는 끼지 않는다.
    <fieldset ref={groupRef} tabIndex={-1}>
      <legend>연결된 식당</legend>

      {linksQuery.isPending && <p>불러오는 중…</p>}
      {linksQuery.isError && (
        <p className="error" role="alert">{errorMessage(linksQuery.error)}</p>
      )}

      {linksQuery.isSuccess && links.length === 0 && (
        <p className="linked-empty">
          아직 연결된 식당이 없어요. 식당 화면에서 이 메뉴에 식당을 연결하면 거리 필터로 뽑을 수 있어요.
        </p>
      )}

      {unlinkMutation.isError && (
        <p className="error" role="alert">
          연결을 해제하지 못했습니다. {errorMessage(unlinkMutation.error)}
        </p>
      )}

      {/* Safari + VoiceOver는 list-style:none이 걸린 <ul>의 목록 시맨틱을 지우므로 role을 명시한다. */}
      {links.length > 0 && (
        <ul ref={listRef} tabIndex={-1} className="linked-list" role="list">
          {links.map((link) =>
            editing === link.restaurantId ? (
              <li key={link.restaurantId} className="linked-row">
                <LinkEditor
                  menuId={menuId}
                  link={link}
                  onClose={() => setEditing(null)}
                  onSaved={() => {
                    setEditing(null);
                    invalidate();
                  }}
                />
              </li>
            ) : (
              <li key={link.restaurantId} className="linked-row">
                <div className="card-main">
                  <strong>{link.restaurantName}</strong>
                  {link.rating != null && (
                    <>
                      <span className="weight" aria-hidden="true">
                        {"★".repeat(link.rating) + "☆".repeat(5 - link.rating)}
                      </span>
                      <span className="sr-only">{`별점 5점 만점에 ${link.rating}점`}</span>
                    </>
                  )}
                </div>
                {link.restaurantAddress && (
                  <span className="linked-address">{link.restaurantAddress}</span>
                )}
                {link.memo && <p className="linked-memo">{link.memo}</p>}
                <div className="card-actions">
                  {/* 버튼 이름에 식당 이름을 넣는다. 없으면 요소 목록에 "수정, 연결 해제"만
                      연결 수만큼 반복되어 어느 식당의 것인지 알 수 없다. */}
                  <button
                    type="button"
                    aria-label={`${link.restaurantName} 연결 수정`}
                    onClick={() => setEditing(link.restaurantId)}
                  >
                    수정
                  </button>
                  <button
                    type="button"
                    aria-label={`${link.restaurantName} 연결 해제`}
                    aria-busy={unlinkMutation.isPending}
                    onClick={() => {
                      if (unlinkMutation.isPending) return;
                      // 되돌릴 수 없는 동작이라 무엇을 지우는지 이름으로 확인시킨다.
                      if (window.confirm(`'${link.restaurantName}' 연결을 해제할까요?`)) {
                        unlinkMutation.mutate({
                          restaurantId: link.restaurantId,
                          name: link.restaurantName,
                        });
                      }
                    }}
                  >
                    연결 해제
                  </button>
                </div>
              </li>
            ),
          )}
        </ul>
      )}
    </fieldset>
  );
}

/**
 * 한 연결의 별점·메모를 고치는 줄.
 *
 * <p><b>{@code <form>}을 쓰지 않는다.</b> 이 컴포넌트는 메뉴 수정 {@code <form>} 안에 들어가는데
 * form 중첩은 HTML상 허용되지 않고, 브라우저가 안쪽을 조용히 버려 저장 버튼이 바깥 폼을
 * 제출해 버린다. 같은 이유로 메모 입력의 Enter도 직접 가로채 이 연결만 저장한다 —
 * 그대로 두면 메뉴 전체가 저장되고 편집 중이던 값은 사라진다.
 */
function LinkEditor({
  menuId,
  link,
  onClose,
  onSaved,
}: {
  menuId: number;
  link: MenuRestaurantDetail;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [rating, setRating] = useState(link.rating ?? 0);
  const [memo, setMemo] = useState(link.memo ?? "");

  const saveMutation = useMutation({
    mutationFn: () =>
      updateMenuRestaurant(menuId, link.restaurantId, {
        // 별점은 선택 값이다. 0은 "아직 안 매김"이라 서버의 1~5 범위에 넣지 않고 null로 보낸다.
        rating: rating === 0 ? null : rating,
        memo: memo.trim() || null,
        // 이 줄을 그릴 때 받은 버전을 그대로 돌려보낸다 — 근거는 VersionGuard.
        version: link.version,
      }),
    onSuccess: onSaved,
  });

  const save = () => {
    if (saveMutation.isPending) return;
    saveMutation.mutate();
  };

  return (
    <div className="linked-editor">
      <strong>{link.restaurantName}</strong>

      <div className="field">
        <span id={`rating-label-${link.restaurantId}`}>별점</span>
        <span
          className="weight-picker"
          role="group"
          aria-labelledby={`rating-label-${link.restaurantId}`}
        >
          {[1, 2, 3, 4, 5].map((value) => (
            <button
              key={value}
              type="button"
              {...starToggle(value <= rating)}
              aria-label={`별점 ${value}`}
              onClick={() => setRating(value)}
            >
              {value <= rating ? "★" : "☆"}
            </button>
          ))}
          {/* 0으로 되돌릴 길이 없으면 한번 누른 별점을 취소할 수 없다. */}
          <button type="button" className="auth-inline-button" onClick={() => setRating(0)}>
            별점 지우기
          </button>
        </span>
      </div>

      <label>
        메모
        <input
          value={memo}
          onChange={(e) => setMemo(e.target.value)}
          maxLength={1000}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              // 바깥 메뉴 폼이 제출되는 것을 막고 이 연결만 저장한다.
              e.preventDefault();
              save();
            }
          }}
        />
      </label>

      {saveMutation.isError && (
        <p className="error" role="alert">{errorMessage(saveMutation.error)}</p>
      )}

      <div className="card-actions">
        <button
          type="button"
          aria-busy={saveMutation.isPending}
          aria-disabled={saveMutation.isPending || undefined}
          onClick={save}
        >
          {saveMutation.isPending ? "저장 중…" : "연결 저장"}
        </button>
        <button type="button" onClick={onClose}>취소</button>
      </div>
    </div>
  );
}
