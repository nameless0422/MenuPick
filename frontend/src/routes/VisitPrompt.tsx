import { useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchHistories, markVisited } from "../api/history";
import { apiErrorMessage } from "../api/http";
import {
  describePickedAt,
  PROMPT_LOOKBACK_DAYS,
  readDismissedVisitPrompts,
  rememberDismissedVisitPrompt,
  selectVisitPromptCandidate,
} from "./visitPromptRules";

type Answer = "ate" | "skipped";

/**
 * 픽 화면 맨 위의 "지난번 뽑은 메뉴, 드셨어요?".
 *
 * 무엇을 왜 묻는지는 {@code visitPromptRules.ts}에 있다. 여기서는 화면에서 어떻게 묻는지만 다룬다.
 *
 * <h2>눈에 띄되 막지 않는다</h2>
 *
 * 모달로 띄우지 않는다. 사용자가 픽 화면에 온 목적은 뽑기이고, 답하기 전까지 뽑지 못하게 하면
 * 기록 몇 건을 얻는 대신 핵심 동작을 망가뜨린다. 답하지 않고 뽑기 시작하면 질문은 사라진다
 * ({@code hidden}) — 새 픽이 생기는 순간 "지난번"이 무엇인지도 바뀌기 때문이다.
 */
export default function VisitPrompt({ hidden }: { hidden: boolean }) {
  const headingId = useId();
  const queryClient = useQueryClient();
  const [answer, setAnswer] = useState<Answer | null>(null);
  const doneRef = useRef<HTMLParagraphElement>(null);

  const candidateQuery = useQuery({
    // ["history", ...] 아래에 둔다. 히스토리 화면에서 방문 처리하면 그쪽이 ["history"]를
    // 무효화하므로, 거기서 이미 답한 픽을 여기서 또 묻지 않는다.
    queryKey: ["history", "visit-prompt"],
    queryFn: async () => {
      const { histories } = await fetchHistories(undefined, PROMPT_LOOKBACK_DAYS, 1);
      // 시각은 조회 시점에 한 번 잡는다. 렌더 중에 Date.now()를 부르면 렌더마다 결과가 달라진다.
      const now = Date.now();
      const candidate = selectVisitPromptCandidate(histories, now, readDismissedVisitPrompts());
      return candidate ? { candidate, when: describePickedAt(candidate.recommendedAt, now) } : null;
    },
    // 창을 오갈 때마다 다시 불러오면, 대답한 직후의 결과 문장이 새 조회 결과로 갈려 사라진다.
    // 대답은 이 컴포넌트의 상태(answer)로 반영하고, 다음에 픽 화면에 들어올 때 새로 묻는다.
    refetchOnWindowFocus: false,
  });

  const visitMutation = useMutation({
    mutationFn: (historyId: number) => markVisited(historyId),
    onSuccess: () => {
      setAnswer("ate");
      // 히스토리 목록과 달력이 캐시에 남아 있으면 그쪽으로 넘어갔을 때 방금 처리한 픽이 잠깐
      // "미방문"으로 보인다. 단 이 질문 자신은 제외한다 — 다시 불러오면 방문 처리된 픽은 대상에서
      // 빠져 질문 카드가 통째로 사라지고, 방금 띄운 "기록했어요"도 함께 없어진다.
      void queryClient.invalidateQueries({
        queryKey: ["history"],
        predicate: (query) => query.queryKey[1] !== "visit-prompt",
      });
      void queryClient.invalidateQueries({ queryKey: ["history-calendar"] });
    },
  });

  // 대답하면 버튼이 사라진다. 방금 누른 버튼이 없어지면 초점이 <body>로 떨어져 키보드·
  // 스크린리더 사용자는 무슨 일이 일어났는지도, 어디 있는지도 모르게 된다. 결과 문장으로 옮긴다.
  useEffect(() => {
    if (answer) doneRef.current?.focus();
  }, [answer]);

  const prompt = candidateQuery.data;
  // 불러오는 중·실패·물을 것 없음은 모두 아무것도 그리지 않는다. 이 질문은 곁다리라
  // "불러오는 중…"이나 오류로 픽 화면 맨 위를 차지할 이유가 없다.
  if (hidden || !prompt) return null;
  const { candidate, when } = prompt;

  if (answer) {
    return (
      <section className="card visit-prompt" aria-labelledby={headingId}>
        <h2 id={headingId} className="sr-only">지난번 픽 기록</h2>
        <p ref={doneRef} tabIndex={-1} role="status">
          {answer === "ate" ? (
            <>
              기록했어요. <Link to="/history">히스토리</Link>의 방문 기록 달력에서 볼 수 있어요.
            </>
          ) : (
            "알겠어요. 이 픽은 다시 묻지 않을게요."
          )}
        </p>
      </section>
    );
  }

  return (
    <section className="card visit-prompt" aria-labelledby={headingId}>
      <h2 id={headingId}>
        {when}에 뽑은 <strong>{candidate.menuName}</strong>, 드셨어요?
      </h2>

      {visitMutation.isError && (
        <p className="error" role="alert">{apiErrorMessage(visitMutation.error)}</p>
      )}

      <div className="card-actions">
        <button
          type="button"
          aria-busy={visitMutation.isPending}
          aria-disabled={visitMutation.isPending || undefined}
          onClick={() => {
            // aria-disabled는 표시일 뿐 클릭을 막지 않는다. 이 조기 반환이 중복 요청을 막는다.
            if (visitMutation.isPending) return;
            visitMutation.mutate(candidate.id);
          }}
        >
          {visitMutation.isPending ? "기록 중…" : "먹었어요"}
        </button>
        <button
          type="button"
          onClick={() => {
            if (visitMutation.isPending) return;
            rememberDismissedVisitPrompt(candidate.id);
            setAnswer("skipped");
          }}
        >
          안 먹었어요
        </button>
      </div>
    </section>
  );
}
