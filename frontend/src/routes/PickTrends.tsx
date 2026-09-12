import { useEffect, useId, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  fetchTrends,
  type TrendEntry,
} from "../api/trends";
import { CATEGORY_PRESETS } from "../constants";
import {
  MAX_TREND_LABELS,
  TREND_MAX_AGE_MS,
  trendComputedAtMillis,
  validateTrendsResponse,
} from "./trendValidation";

function ordered(entries: TrendEntry[], maxLabels: number) {
  return [...entries]
    .sort((left, right) => left.rankOrder - right.rankOrder)
    .slice(0, Math.min(maxLabels, MAX_TREND_LABELS));
}

function formatComputedAt(value: string) {
  const timestamp = trendComputedAtMillis(value);
  if (timestamp === null) return "";
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(timestamp);
}

export default function PickTrends({
  busy,
  onPickCategory,
}: {
  busy: boolean;
  onPickCategory: (category: string) => void;
}) {
  const headingId = useId();
  const [now] = useState(() => Date.now());
  const [expiredComputedAt, setExpiredComputedAt] = useState<string | null>(null);
  const query = useQuery({ queryKey: ["pick-trends"], queryFn: fetchTrends });
  const { refetch } = query;
  const data = useMemo(() => validateTrendsResponse(query.data), [query.data]);
  const computedAtMs = trendComputedAtMillis(data?.computedAt);
  // 새 응답을 받은 시각을 포함해야 만료 직후 도착한 새 스냅샷을 "미래"로 오판하지 않는다.
  const observedAt = Math.max(now, query.dataUpdatedAt);
  const fresh = data?.status === "READY"
    && computedAtMs !== null
    && computedAtMs <= observedAt
    && observedAt - computedAtMs <= TREND_MAX_AGE_MS
    && expiredComputedAt !== data.computedAt;

  useEffect(() => {
    if (!data || data.status !== "READY" || computedAtMs === null || !fresh) return;
    const remaining = computedAtMs + TREND_MAX_AGE_MS - observedAt;
    const timer = window.setTimeout(() => {
      setExpiredComputedAt(data.computedAt ?? null);
      void refetch();
    }, Math.max(0, remaining) + 1);
    return () => window.clearTimeout(timer);
  }, [computedAtMs, data, fresh, observedAt, refetch]);

  // 이 정보는 픽의 보조 정보다. 로딩·오류·비활성·계약 위반이 핵심 동작을 막지 않는다.
  if (!data || query.isError || data.status === "DISABLED") return null;

  if (data.status === "NOT_READY") {
    return (
      <section className="card pick-trends pick-trends-muted" aria-labelledby={headingId}>
        <h2 id={headingId}>최근 선택·방문한 메뉴</h2>
        <p>아직 트렌드를 보여드릴 만큼 선택·방문 기록이 모이지 않았어요.</p>
      </section>
    );
  }

  if (data.status === "STALE") {
    return (
      <section className="card pick-trends pick-trends-muted" aria-labelledby={headingId}>
        <h2 id={headingId}>최근 선택·방문한 메뉴</h2>
        <p>트렌드를 새로 집계하고 있어요. 최신 결과가 준비되면 보여드릴게요.</p>
      </section>
    );
  }

  // READY라도 미래·36시간 초과·누락 시각이면 인원 수를 절대 노출하지 않는다.
  if (!fresh) return null;

  const categories = ordered(data.categories, data.maxLabels)
    .filter((entry) => CATEGORY_PRESETS.includes(entry.label));
  const menus = ordered(data.menus, data.maxLabels);

  return (
    <section className="card pick-trends" aria-labelledby={headingId}>
      <div className="pick-trends-heading">
        <div>
          <h2 id={headingId}>최근 선택·방문한 메뉴</h2>
          <p>최근 {data.windowDays}일 동안 추천받아 선택했거나 방문한 사용자 기준이에요.</p>
        </div>
        <time dateTime={data.computedAt ?? undefined}>
          {formatComputedAt(data.computedAt!)} KST 집계
        </time>
      </div>

      {categories.length === 0 && menus.length === 0 ? (
        <p>사용자 {data.minUsers}명 이상이 선택하거나 방문한 항목이 아직 없어요.</p>
      ) : (
        <div className="pick-trends-groups">
          {categories.length > 0 && (
            <div>
              <h3>인기 카테고리</h3>
              <ol className="pick-trends-list">
                {categories.map((entry) => (
                  <li key={`category-${entry.rankOrder}-${entry.label}`}>
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => onPickCategory(entry.label)}
                    >
                      <span>{entry.label}</span><small>{entry.userCount}명</small>
                    </button>
                  </li>
                ))}
              </ol>
            </div>
          )}
          {menus.length > 0 && (
            <div>
              <h3>인기 메뉴</h3>
              <ol className="pick-trends-list pick-trends-menu-list">
                {menus.map((entry) => (
                  <li key={`menu-${entry.rankOrder}-${entry.label}`}>
                    <span>{entry.label}</span><small>{entry.userCount}명</small>
                  </li>
                ))}
              </ol>
            </div>
          )}
        </div>
      )}
      <p className="pick-trends-privacy">개별 사용자의 기록은 표시하지 않아요.</p>
    </section>
  );
}
