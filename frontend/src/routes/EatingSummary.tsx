import { useId, useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { fetchEatingSummary, type ForgottenMenu } from "../api/history";
import { apiErrorMessage } from "../api/http";

/**
 * 내 식사 기록 요약 — 히스토리 화면 맨 위의 "요즘 뭘 먹었나".
 *
 * <h2>집단 통계와 다른 점</h2>
 *
 * 픽 화면의 "요즘 많이 먹은 것"은 남들의 기록이라 최소 인원을 못 넘기면 아무것도 안 보인다.
 * 여기는 내 기록이라 그런 문턱이 없다. 한 건이어도 내 한 건이다.
 *
 * <h2>비어 있을 때가 중요하다</h2>
 *
 * 지금 운영에는 방문 기록이 0건이다. 그러니 이 화면의 첫인상은 대부분 빈 상태이고, 그때
 * "아직 없다"가 아니라 <b>무엇을 하면 채워지는지</b>를 말해야 한다. 빈 목록을 그려 두면
 * 사용자는 기능이 고장 난 줄 안다.
 */
export default function EatingSummary() {
  const headingId = useId();
  // 렌더 중에 Date.now()를 부르면 리렌더마다 "12일 전"이 흔들릴 수 있고, 렌더를 순수하게
  // 유지하라는 린트 규칙에도 걸린다. 마운트할 때 한 번만 잡는다.
  const [now] = useState(() => Date.now());
  const summaryQuery = useQuery({
    queryKey: ["history", "summary"],
    queryFn: () => fetchEatingSummary(),
  });

  if (summaryQuery.isPending) return null;

  if (summaryQuery.isError) {
    return (
      <section className="card eating-summary" aria-labelledby={headingId}>
        <h2 id={headingId}>내 식사 기록</h2>
        <p className="error" role="alert">{apiErrorMessage(summaryQuery.error)}</p>
      </section>
    );
  }

  const { periodDays, picks, eaten, categories, menus, forgottenMenus } = summaryQuery.data;

  return (
    <section className="card eating-summary" aria-labelledby={headingId}>
      <h2 id={headingId}>내 식사 기록</h2>

      <p className="settings-desc">
        최근 {periodDays}일 · 뽑은 <strong>{picks}번</strong> 중{" "}
        <strong>{eaten}번</strong> 드셨어요
      </p>

      {eaten === 0 ? (
        // 0건이 지금의 기본 상태다. 무엇을 누르면 채워지는지까지 말한다.
        <p className="card-muted-hint">
          아직 먹은 기록이 없어요. 뽑은 메뉴를 드셨다면 <strong>방문했어요</strong>를 눌러 주세요.
          {picks === 0 && (
            <>
              {" "}
              <Link to="/pick">먼저 한 번 뽑아보기 →</Link>
            </>
          )}
        </p>
      ) : (
        <div className="eating-summary-lists">
          {categories.length > 0 && (
            <div>
              <h3>많이 먹은 카테고리</h3>
              <ul className="chip-row" role="list">
                {categories.map((entry) => (
                  <li key={entry.label} className="chip chip-tag">
                    {entry.label} <span className="eating-summary-count">{entry.count}번</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {menus.length > 0 && (
            <div>
              <h3>많이 먹은 메뉴</h3>
              <ul className="chip-row" role="list">
                {menus.map((entry) => (
                  <li key={entry.label} className="chip chip-tag">
                    {entry.label} <span className="eating-summary-count">{entry.count}번</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {forgottenMenus.length > 0 && (
        <div>
          <h3>오랜만에 어때요?</h3>
          {/* 이 목록만 최근 기간이 아니라 전 기간을 본다 — "마지막이 언제였나"가 질문이다. */}
          <ul className="chip-row" role="list">
            {forgottenMenus.map((menu) => (
              <li key={menu.menuId} className="chip">
                {menu.name} <span className="eating-summary-count">{lastPickedLabel(menu, now)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

/**
 * "아직 안 뽑힘" / "12일 전".
 *
 * 날짜를 그대로 적지 않는 이유는 이 목록이 묻는 것이 날짜가 아니라 <b>얼마나 오래됐나</b>이기
 * 때문이다. "8월 3일"은 머릿속에서 한 번 더 빼야 한다.
 */
function lastPickedLabel(menu: ForgottenMenu, now: number): string {
  if (!menu.lastPickedAt) return "아직 안 뽑힘";
  const picked = new Date(menu.lastPickedAt);
  if (Number.isNaN(picked.getTime())) return "";
  const days = Math.floor((now - picked.getTime()) / (24 * 60 * 60 * 1000));
  if (days <= 0) return "오늘";
  if (days === 1) return "어제";
  return `${days}일 전`;
}
