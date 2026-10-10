import { useEffect, useId, useRef, useState } from "react";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { fetchDeletedRestaurants, restoreRestaurant, type DeletedRestaurantSummary } from "../api/restaurants";
import { apiErrorCode, apiErrorMessage } from "../api/http";
import { KST_OFFSET_MS, kstLocalDateTimeMillis } from "./kstTime";

function deletedDate(value: string) {
  const millis = kstLocalDateTimeMillis(value);
  return millis === null ? null : new Date(millis + KST_OFFSET_MS).toISOString().slice(0, 10);
}

export default function RestaurantTrash() {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [message, setMessage] = useState("");
  const headingId = useId();
  const contentId = useId();
  const toggleRef = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const restoredRef = useRef<number | null>(null);
  const trashQuery = useInfiniteQuery({
    queryKey: ["deleted-restaurants"],
    queryFn: ({ pageParam }) => fetchDeletedRestaurants(pageParam, 20),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.hasNext && last.nextCursor != null ? last.nextCursor : undefined,
    enabled: open,
  });
  const restaurants = trashQuery.data?.pages.flatMap((page) => page.restaurants) ?? [];

  useEffect(() => {
    const restoredId = restoredRef.current;
    if (restoredId === null || trashQuery.isFetching) return;
    restoredRef.current = null;
    const visible = trashQuery.data?.pages.some((page) => page.restaurants.some((restaurant) => restaurant.id === restoredId));
    if (!visible && document.activeElement === document.body) {
      (listRef.current ?? toggleRef.current)?.focus();
    }
  }, [trashQuery.data, trashQuery.isFetching]);

  const restoreMutation = useMutation({
    mutationFn: (restaurant: DeletedRestaurantSummary) => restoreRestaurant(restaurant.id, restaurant.version),
    onMutate: () => setMessage(""),
    onSuccess: (_, restaurant) => {
      restoredRef.current = restaurant.id;
      setMessage(`'${restaurant.name}' 식당을 복원했습니다.`);
      return Promise.all([
        queryClient.invalidateQueries({ queryKey: ["deleted-restaurants"] }),
        queryClient.invalidateQueries({ queryKey: ["restaurants"] }),
        queryClient.invalidateQueries({ queryKey: ["restaurant", restaurant.id] }),
        queryClient.invalidateQueries({ queryKey: ["menu-restaurants"] }),
        queryClient.invalidateQueries({ queryKey: ["history"] }),
      ]);
    },
    onError: (error) => {
      if (apiErrorCode(error) === "CONCURRENT_MODIFICATION" || apiErrorCode(error) === "RESTAURANT_NOT_FOUND") {
        void queryClient.invalidateQueries({ queryKey: ["deleted-restaurants"] });
      }
    },
  });
  const restoreBlocked = restoreMutation.isPending || trashQuery.isFetching;

  return (
    <section className="menu-trash" aria-labelledby={headingId}>
      <h2 id={headingId}>식당 휴지통</h2>
      <button
        ref={toggleRef}
        type="button"
        aria-expanded={open}
        aria-controls={contentId}
        onClick={() => {
          if (!open) { setMessage(""); restoreMutation.reset(); }
          setOpen(!open);
        }}
      >{open ? "휴지통 닫기" : "휴지통 보기"}</button>
      <p role={open || message ? "status" : undefined}>{message || (open && trashQuery.isPending ? "삭제한 식당을 불러오는 중…"
        : open && trashQuery.isSuccess ? `휴지통 식당 ${restaurants.length}개` : "")}</p>
      <div id={contentId} hidden={!open}>
        {open && <>
          <p>식당 이름·주소·전화·위치·지도 링크를 삭제 전 정보로 복원합니다.
            삭제할 때 해제한 메뉴 연결과 연결별 별점·메모는 복원되지 않습니다. 메뉴를 다시 연결하세요.</p>
          <p>최근 삭제한 식당부터 표시합니다. 삭제일은 한국 시각 기준입니다.</p>
          <button
            type="button"
            aria-disabled={trashQuery.isFetching || undefined}
            aria-busy={trashQuery.isFetching}
            onClick={() => { if (!trashQuery.isFetching) void trashQuery.refetch(); }}
          >식당 휴지통 새로고침</button>
          {trashQuery.isError && <p className="error" role="alert">{apiErrorMessage(trashQuery.error)}</p>}
          {restoreMutation.isError && <p className="error" role="alert">{apiErrorMessage(restoreMutation.error)}</p>}
          {trashQuery.isSuccess && restaurants.length === 0 && <p>삭제한 식당이 없습니다.</p>}
          {restaurants.length > 0 && (
            <ul ref={listRef} tabIndex={-1} className="card-list" role="list" aria-label="삭제한 식당">
              {restaurants.map((restaurant) => {
                const date = deletedDate(restaurant.deletedAt);
                return <li key={restaurant.id} className="card">
                  <strong>{restaurant.name}</strong>
                  {restaurant.address && <p>{restaurant.address}</p>}
                  <time dateTime={date ?? undefined}>삭제일: {date ?? "날짜 정보 없음"}</time>
                  <button
                    type="button"
                    aria-label={`${restaurant.name} 복원`}
                    aria-disabled={restoreBlocked || undefined}
                    aria-busy={restoreMutation.isPending && restoreMutation.variables?.id === restaurant.id}
                    onClick={() => { if (!restoreBlocked) restoreMutation.mutate(restaurant); }}
                  >{restoreMutation.isPending && restoreMutation.variables?.id === restaurant.id ? "복원 중…" : "복원"}</button>
                </li>;
              })}
            </ul>
          )}
          {trashQuery.hasNextPage && <button
            type="button"
            aria-disabled={trashQuery.isFetching || undefined}
            aria-busy={trashQuery.isFetchingNextPage}
            onClick={() => { if (!trashQuery.isFetching) void trashQuery.fetchNextPage(); }}
          >{trashQuery.isFetchingNextPage ? "삭제한 식당를 더 불러오는 중…" : "삭제한 식당 더 보기"}</button>}
        </>}
      </div>
    </section>
  );
}
