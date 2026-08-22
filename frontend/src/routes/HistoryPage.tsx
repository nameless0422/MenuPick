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

// "전체"는 3650일이라 작년 기록이 그대로 섞인다. 연도를 늘 붙이면 최근 기록이 장황해지므로
// 올해가 아닐 때만 붙인다 — 그래야 "8월 21일"이 어느 해의 것인지 헷갈리지 않는다.
function formatDateTime(iso: string): string {
  const d = new Date(iso);
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  const year = d.getFullYear() === new Date().getFullYear() ? "" : `${d.getFullYear()}년 `;
  return `${year}${d.getMonth() + 1}월 ${d.getDate()}일 (${WEEKDAYS[d.getDay()]}) ${hh}:${mm}`;
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

  // 삭제가 끝나면 그 기록은 목록에서 사라진다 — 성공 안내에 이름을 넣으려면 변이 인자에
  // 이름을 함께 실어 두는 수밖에 없다.
  const deleteMutation = useMutation({
    mutationFn: ({ id }: { id: number; label: string }) => deleteHistory(id),
    onSettled: invalidate,
  });

  const histories = historyQuery.data?.pages.flatMap((page) => page.histories) ?? [];

  // 조사(을/를)는 받침에 따라 갈리므로 이름 뒤에 "픽 기록을"을 붙여 이름과 조사를 떼어 놓는다.
  const listAnnouncement = historyQuery.isFetchingNextPage
    ? "픽 기록을 더 불러오는 중…"
    : deleteMutation.isSuccess
      ? `'${deleteMutation.variables.label}' 픽 기록을 삭제했습니다. 픽 기록 ${histories.length}개.`
      : `픽 기록 ${histories.length}개`;

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

      {historyQuery.isError && <p className="error" role="alert">{errorMessage(historyQuery.error)}</p>}

      {/* 삭제 실패 시 목록만 새로고침되어 항목이 그대로 남는다 — 이유를 알려야 한다 */}
      {deleteMutation.isError && (
        <p className="error" role="alert">삭제하지 못했습니다. {errorMessage(deleteMutation.error)}</p>
      )}
      {/* 기간 필터를 바꾸면 목록이 통째로 갈리는데 화면 아래가 조용히 다시 그려질 뿐이다.
          리전은 마운트 시점부터(비어 있더라도) DOM에 있어야 한다 — 내용과 함께 뒤늦게
          삽입되는 라이브 리전은 통지되지 않는다. */}
      <div role="status">
        {historyQuery.isPending && <p>불러오는 중…</p>}
        {historyQuery.isSuccess && histories.length === 0 && (
          <p>
            아직 픽 기록이 없어요. <Link to="/pick">오늘 뭐 먹을지 골라볼까요?</Link>
          </p>
        )}
        {historyQuery.isSuccess && histories.length > 0 && (
          <p className="sr-only">{listAnnouncement}</p>
        )}
      </div>

      {/* 결과가 0건일 때 빈 <ul>을 남기면 "목록, 항목 0개"로 읽혀 위 안내와 어긋난다.
          Safari + VoiceOver는 list-style: none이 걸린 <ul>의 목록 시맨틱을 지우므로
          role도 명시한다. */}
      {histories.length > 0 && (
      <ul className="card-list" role="list">
        {histories.map((history) => {
          // 화면에 보이는 이름, 버튼 이름, 확인 대화상자가 전부 같은 말을 쓰게 한 번만 만든다.
          const label = history.menuName ?? "삭제된 메뉴";
          return (
          <li key={history.id} className="card">
            <div className="card-main">
              <strong>{label}</strong>
              <time className="history-time" dateTime={history.recommendedAt}>
                {formatDateTime(history.recommendedAt)}
              </time>
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
                  <span className="history-visited-at">
                  방문 시각{" "}
                  <time dateTime={history.visitedAt}>{formatDateTime(history.visitedAt)}</time>
                </span>
                )
              ) : (
                <VisitAction
                  history={history}
                  menuId={history.menuName != null ? menuIdByName.get(history.menuName) ?? null : null}
                  onVisited={invalidate}
                />
              )}
              {/* 이름 없는 "삭제"가 기록 수만큼 늘어서고, 확인 대화상자마저 무엇을 지우는지
                  말하지 않아 되돌릴 수 없는 동작을 눈으로 확인할 방법이 없었다. 같은 메뉴를
                  여러 번 뽑을 수 있으므로 시각까지 넣어야 항목이 서로 구분된다. */}
              <button
                disabled={deleteMutation.isPending}
                aria-label={`${label} 픽 기록 삭제 (${formatDateTime(history.recommendedAt)})`}
                onClick={() => {
                  if (window.confirm(`'${label}' 픽 기록을 삭제할까요?`)) {
                    deleteMutation.mutate({ id: history.id, label });
                  }
                }}
              >
                삭제
              </button>
            </div>
          </li>
          );
        })}
      </ul>
      )}

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

  // 한 카드에 방문 확정·취소·삭제 버튼이 함께 있어, 이름이 없으면 각 컨트롤이 무엇을
  // 하는 것인지 알 수 없다. 히스토리 항목이 여러 개면 서로 구분되지도 않는다.
  // 콤보박스에만 있던 이 처리를 같은 카드의 버튼들에도 똑같이 적용한다.
  const label = history.menuName ?? "이 픽";

  if (candidates) {
    return (
      <span className="visit-choice">
        <select
          aria-label={`${label} 방문 식당 선택`}
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
          aria-label={`${label} ${visitMutation.isPending ? "처리 중" : "선택한 식당으로 방문 확정"}`}
          onClick={() => visitMutation.mutate(selectedRestaurantId)}
        >
          {visitMutation.isPending ? "처리 중…" : "이 식당으로 방문 확정"}
        </button>
        <button
          type="button"
          aria-label={`${label} 방문 식당 선택 취소`}
          onClick={() => setCandidates(null)}
        >
          취소
        </button>
      </span>
    );
  }

  return (
    <span className="visit-action">
      <button
        disabled={loadingCandidates || visitMutation.isPending}
        aria-label={`${label} ${loadingCandidates || visitMutation.isPending ? "처리 중" : "방문했어요"}`}
        onClick={startVisit}
      >
        {loadingCandidates || visitMutation.isPending ? "처리 중…" : "방문했어요"}
      </button>
      {(loadError || visitMutation.isError) && (
        <span className="error" role="alert">{loadError ?? errorMessage(visitMutation.error)}</span>
      )}
    </span>
  );
}
