import { useState } from "react";
import { Link } from "react-router-dom";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  deleteHistory,
  fetchHistories,
  fetchMenuRestaurants,
  markVisited,
  type HistoryFilterCondition,
  type HistorySummary,
  type MenuRestaurant,
} from "../api/history";
import { fetchMenus } from "../api/menus";
import { apiErrorMessage as errorMessage } from "../api/http";
import "./HistoryPage.css";

// "전체" 필터는 백엔드에 별도 옵션이 없어 충분히 큰 값을 넘겨 사실상 전체 기간을 조회한다.
// days를 아예 생략하면 백엔드가 7일로 대체하고(HistoryService), 0 이하는 @Min(1)에 걸려 400이다.
const DAYS_OPTIONS: { label: string; value: number }[] = [
  { label: "7일", value: 7 },
  { label: "30일", value: 30 },
  { label: "전체", value: 3650 },
];

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

function formatDateTime(iso: string): string {
  const d = new Date(iso);
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  return `${d.getMonth() + 1}월 ${d.getDate()}일 (${WEEKDAYS[d.getDay()]}) ${hh}:${mm}`;
}

function filterLabel(condition: HistoryFilterCondition): string {
  switch (condition.filterType) {
    case "CATEGORY":
      return `카테고리 ${condition.filterValue}`;
    case "TAG_INCLUDE":
      return `#${condition.filterValue}`;
    case "TAG_EXCLUDE":
      return `제외 #${condition.filterValue}`;
    case "MAX_DISTANCE":
      return `거리 ${condition.filterValue}m`;
    default:
      return `${condition.filterType} ${condition.filterValue}`;
  }
}

function filterChipClass(condition: HistoryFilterCondition): string {
  if (condition.filterType === "TAG_INCLUDE") return "chip chip-tag";
  if (condition.filterType === "TAG_EXCLUDE") return "chip chip-warn";
  return "chip";
}

export default function HistoryPage() {
  const queryClient = useQueryClient();
  const [days, setDays] = useState(7);

  const historyQuery = useInfiniteQuery({
    queryKey: ["history", days],
    queryFn: ({ pageParam }) => fetchHistories(pageParam, days),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (last) => (last.hasNext && last.nextCursor != null ? last.nextCursor : undefined),
  });

  // 방문 식당 선택 UI를 위해 메뉴 이름 → 메뉴 ID를 미리 확보해둔다.
  // HistorySummary에는 menuId가 내려오지 않으므로(메뉴 이름만 제공), 내 메뉴 목록에서 이름이
  // 유일하게 일치하는 경우에만 연결 식당을 조회할 수 있게 한다 — 이름이 중복되면 어느 메뉴인지
  // 확정할 수 없어 선택 UI를 생략하고 기본 방문 처리만 제공한다.
  const menusQuery = useQuery({
    queryKey: ["menus-for-history"],
    queryFn: () => fetchMenus(undefined, 100),
  });

  const menuIdByName = new Map<string, number | null>();
  for (const menu of menusQuery.data?.menus ?? []) {
    menuIdByName.set(menu.name, menuIdByName.has(menu.name) ? null : menu.id);
  }

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["history"] });

  const deleteMutation = useMutation({ mutationFn: deleteHistory, onSettled: invalidate });

  const histories = historyQuery.data?.pages.flatMap((page) => page.histories) ?? [];

  return (
    <div className="page">
      <header className="page-header">
        <h1>픽 히스토리</h1>
      </header>

      <div className="days-filter">
        {DAYS_OPTIONS.map((option) => (
          <button
            key={option.value}
            type="button"
            className={days === option.value ? "active" : ""}
            // 선택된 기간을 색으로만 알리면 스크린리더에는 "7일, 버튼"으로만 읽혀
            // 지금 어느 기간을 보고 있는지 알 수 없다.
            aria-pressed={days === option.value}
            onClick={() => setDays(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>

      {historyQuery.isPending && <p>불러오는 중…</p>}
      {historyQuery.isError && <p className="error">{errorMessage(historyQuery.error)}</p>}

      {/* 삭제 실패 시 목록만 새로고침되어 항목이 그대로 남는다 — 이유를 알려야 한다 */}
      {deleteMutation.isError && (
        <p className="error" role="alert">삭제하지 못했습니다. {errorMessage(deleteMutation.error)}</p>
      )}
      {historyQuery.isSuccess && histories.length === 0 && (
        <p>
          아직 픽 기록이 없어요. <Link to="/pick">오늘 뭐 먹을지 골라볼까요?</Link>
        </p>
      )}

      <ul className="card-list">
        {histories.map((history) => (
          <li key={history.id} className="card">
            <div className="card-main">
              <strong>{history.menuName ?? "삭제된 메뉴"}</strong>
              <span className="history-time">{formatDateTime(history.recommendedAt)}</span>
              {history.isVisited ? (
                <span className="chip chip-tag">방문완료</span>
              ) : (
                <span className="chip">미방문</span>
              )}
            </div>

            <p className="history-restaurant">
              {history.restaurantName ?? "기록된 식당 없음"}
            </p>

            {history.filterConditions.length > 0 && (
              <div className="chip-row">
                {history.filterConditions.map((condition, index) => (
                  <span key={index} className={filterChipClass(condition)}>
                    {filterLabel(condition)}
                  </span>
                ))}
              </div>
            )}

            <div className="card-actions">
              {history.isVisited ? (
                history.visitedAt && (
                  <span className="history-visited-at">방문 시각 {formatDateTime(history.visitedAt)}</span>
                )
              ) : (
                <VisitAction
                  history={history}
                  menuId={history.menuName != null ? menuIdByName.get(history.menuName) ?? null : null}
                  onVisited={invalidate}
                />
              )}
              <button
                disabled={deleteMutation.isPending}
                onClick={() => {
                  if (window.confirm("이 픽 기록을 삭제할까요?")) {
                    deleteMutation.mutate(history.id);
                  }
                }}
              >
                삭제
              </button>
            </div>
          </li>
        ))}
      </ul>

      {historyQuery.hasNextPage && (
        <button
          disabled={historyQuery.isFetchingNextPage}
          onClick={() => historyQuery.fetchNextPage()}
        >
          {historyQuery.isFetchingNextPage ? "불러오는 중…" : "더 보기"}
        </button>
      )}
    </div>
  );
}

function VisitAction({
  history,
  menuId,
  onVisited,
}: {
  history: HistorySummary;
  menuId: number | null;
  onVisited: () => void;
}) {
  const [candidates, setCandidates] = useState<MenuRestaurant[] | null>(null);
  const [selectedRestaurantId, setSelectedRestaurantId] = useState<number | undefined>(undefined);
  const [loadingCandidates, setLoadingCandidates] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const visitMutation = useMutation({
    mutationFn: (restaurantId?: number) => markVisited(history.id, restaurantId),
    onSuccess: onVisited,
  });

  const startVisit = async () => {
    if (menuId == null) {
      visitMutation.mutate(undefined);
      return;
    }
    setLoadError(null);
    setLoadingCandidates(true);
    try {
      const restaurants = await fetchMenuRestaurants(menuId);
      if (restaurants.length >= 2) {
        setCandidates(restaurants);
        setSelectedRestaurantId(restaurants[0].restaurantId);
      } else {
        visitMutation.mutate(restaurants[0]?.restaurantId);
      }
    } catch (error) {
      setLoadError(errorMessage(error));
    } finally {
      setLoadingCandidates(false);
    }
  };

  if (candidates) {
    return (
      <span className="visit-choice">
        <select
          value={selectedRestaurantId}
          onChange={(e) => setSelectedRestaurantId(Number(e.target.value))}
        >
          {candidates.map((candidate) => (
            <option key={candidate.restaurantId} value={candidate.restaurantId}>
              {candidate.restaurantName}
            </option>
          ))}
        </select>
        <button
          disabled={visitMutation.isPending}
          onClick={() => visitMutation.mutate(selectedRestaurantId)}
        >
          {visitMutation.isPending ? "처리 중…" : "이 식당으로 방문 확정"}
        </button>
        <button type="button" onClick={() => setCandidates(null)}>취소</button>
      </span>
    );
  }

  return (
    <span className="visit-action">
      <button disabled={loadingCandidates || visitMutation.isPending} onClick={startVisit}>
        {loadingCandidates || visitMutation.isPending ? "처리 중…" : "방문했어요"}
      </button>
      {(loadError || visitMutation.isError) && (
        <span className="error">{loadError ?? errorMessage(visitMutation.error)}</span>
      )}
    </span>
  );
}
