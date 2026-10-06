import { useEffect, useId, useRef, useState } from "react";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { fetchDeletedMenus, restoreMenu, type DeletedMenuSummary } from "../api/menus";
import { apiErrorCode, apiErrorMessage } from "../api/http";
import { KST_OFFSET_MS, kstLocalDateTimeMillis } from "./kstTime";

function deletedDate(value: string) {
  const millis = kstLocalDateTimeMillis(value);
  return millis === null ? null : new Date(millis + KST_OFFSET_MS).toISOString().slice(0, 10);
}

export default function MenuTrash() {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [message, setMessage] = useState("");
  const headingId = useId();
  const contentId = useId();
  const toggleRef = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const restoredRef = useRef<number | null>(null);
  const trashQuery = useInfiniteQuery({
    queryKey: ["deleted-menus"],
    queryFn: ({ pageParam }) => fetchDeletedMenus(pageParam, 20),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.hasNext && last.nextCursor != null ? last.nextCursor : undefined,
    enabled: open,
  });
  const menus = trashQuery.data?.pages.flatMap((page) => page.menus) ?? [];

  useEffect(() => {
    const restoredId = restoredRef.current;
    if (restoredId === null || trashQuery.isFetching) return;
    restoredRef.current = null;
    const visible = trashQuery.data?.pages.some((page) => page.menus.some((menu) => menu.id === restoredId));
    if (!visible && document.activeElement === document.body) {
      (listRef.current ?? toggleRef.current)?.focus();
    }
  }, [trashQuery.data, trashQuery.isFetching]);

  const restoreMutation = useMutation({
    mutationFn: (menu: DeletedMenuSummary) => restoreMenu(menu.id, menu.version),
    onMutate: () => setMessage(""),
    onSuccess: (_, menu) => {
      restoredRef.current = menu.id;
      setMessage(`'${menu.name}' 메뉴를 복원했습니다.`);
      return Promise.all([
        queryClient.invalidateQueries({ queryKey: ["deleted-menus"] }),
        queryClient.invalidateQueries({ queryKey: ["menus"] }),
        queryClient.invalidateQueries({ queryKey: ["menu", menu.id] }),
        queryClient.invalidateQueries({ queryKey: ["menu-restaurants", menu.id] }),
        queryClient.invalidateQueries({ queryKey: ["history"] }),
      ]);
    },
    onError: (error) => {
      if (apiErrorCode(error) === "CONCURRENT_MODIFICATION" || apiErrorCode(error) === "MENU_NOT_FOUND") {
        void queryClient.invalidateQueries({ queryKey: ["deleted-menus"] });
      }
    },
  });
  const restoreBlocked = restoreMutation.isPending || trashQuery.isFetching;

  return (
    <section className="menu-trash" aria-labelledby={headingId}>
      <h2 id={headingId}>메뉴 휴지통</h2>
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
      <p role={open || message ? "status" : undefined}>{message || (open && trashQuery.isPending ? "삭제한 메뉴를 불러오는 중…"
        : open && trashQuery.isSuccess ? `휴지통 메뉴 ${menus.length}개` : "")}</p>
      <div id={contentId} hidden={!open}>
        {open && <>
          <p>메뉴를 복원하면 기존 메모·태그·식당 연결과 추천 제외·쉬기 설정을 유지합니다.
            별도로 삭제한 태그나 식당은 되살리지 않습니다.</p>
          <p>최근 삭제한 메뉴부터 표시합니다. 삭제일은 한국 시각 기준입니다.</p>
          <button
            type="button"
            aria-disabled={trashQuery.isFetching || undefined}
            aria-busy={trashQuery.isFetching}
            onClick={() => { if (!trashQuery.isFetching) void trashQuery.refetch(); }}
          >메뉴 휴지통 새로고침</button>
          {trashQuery.isError && <p className="error" role="alert">{apiErrorMessage(trashQuery.error)}</p>}
          {restoreMutation.isError && <p className="error" role="alert">{apiErrorMessage(restoreMutation.error)}</p>}
          {trashQuery.isSuccess && menus.length === 0 && <p>삭제한 메뉴가 없습니다.</p>}
          {menus.length > 0 && (
            <ul ref={listRef} tabIndex={-1} className="card-list" role="list" aria-label="삭제한 메뉴">
              {menus.map((menu) => {
                const date = deletedDate(menu.deletedAt);
                return <li key={menu.id} className="card">
                  <strong>{menu.name}</strong>
                  <time dateTime={date ?? undefined}>삭제일: {date ?? "날짜 정보 없음"}</time>
                  <button
                    type="button"
                    aria-label={`${menu.name} 복원`}
                    aria-disabled={restoreBlocked || undefined}
                    aria-busy={restoreMutation.isPending && restoreMutation.variables?.id === menu.id}
                    onClick={() => { if (!restoreBlocked) restoreMutation.mutate(menu); }}
                  >{restoreMutation.isPending && restoreMutation.variables?.id === menu.id ? "복원 중…" : "복원"}</button>
                </li>;
              })}
            </ul>
          )}
          {trashQuery.hasNextPage && <button
            type="button"
            aria-disabled={trashQuery.isFetching || undefined}
            aria-busy={trashQuery.isFetchingNextPage}
            onClick={() => { if (!trashQuery.isFetching) void trashQuery.fetchNextPage(); }}
          >{trashQuery.isFetchingNextPage ? "삭제한 메뉴를 더 불러오는 중…" : "삭제한 메뉴 더 보기"}</button>}
        </>}
      </div>
    </section>
  );
}
