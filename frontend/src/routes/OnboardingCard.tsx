import { useEffect, useId, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  fetchOnboardingStatus,
  isOnboardingSkipped,
  skipOnboarding,
} from "../api/onboarding";
import { batchUpdateExclusions } from "../api/menus";
import { apiErrorMessage } from "../api/http";
import { chipToggle } from "../a11y/chipToggle";

/**
 * 첫 사용자 안내 — "안 드시는 것부터 빼 주세요".
 *
 * <h2>왜 이 화면이 필요한가</h2>
 *
 * 가입하면 기본 메뉴 22개가 그대로 들어온다. 취향이 한 톨도 반영되지 않은 상태라 첫 픽에서
 * 평소 안 먹는 음식이 나오기 쉬운데, 계속 쓸지 정하는 순간이 바로 그 첫 픽이다.
 *
 * <h2>고르게 하지 않고 빼게 한다</h2>
 *
 * 22개 중 좋아하는 것을 고르라고 하면 일이 된다. 못 먹는 것은 대개 몇 개뿐이라, 빼는 쪽이
 * 훨씬 적게 누르고 끝난다. 아무것도 빼지 않고 넘어가도 된다 — 그때는 저장할 것이 없으므로
 * 서버를 부르지 않는다.
 *
 * <h2>언제 사라지는가</h2>
 *
 * 저장하거나 건너뛰면 그 자리에서 닫히고, 다음부터는 서버가 판정한다(픽한 적이 있거나 제외해
 * 둔 메뉴가 있으면 다시 뜨지 않는다). 건너뛰기만 브라우저에 남는다 — 근거는 {@code onboarding.ts}.
 */
export default function OnboardingCard() {
  const headingId = useId();
  const queryClient = useQueryClient();
  // 마운트할 때 한 번만 읽는다. 렌더 중에 저장소를 읽으면 렌더마다 결과가 달라질 수 있다.
  const [skipped, setSkipped] = useState(() => isOnboardingSkipped());
  const [excluded, setExcluded] = useState<Set<number>>(new Set());
  const [done, setDone] = useState(false);
  const doneRef = useRef<HTMLParagraphElement>(null);

  const statusQuery = useQuery({
    queryKey: ["onboarding"],
    queryFn: fetchOnboardingStatus,
    enabled: !skipped,
  });

  const save = useMutation({
    mutationFn: (ids: number[]) =>
      batchUpdateExclusions(ids.map((menuId) => ({ menuId, excluded: true }))),
    onSuccess: () => {
      setDone(true);
      // 픽 후보가 방금 줄었다. 다음 픽이 옛 목록으로 돌지 않게 관련 조회를 무효화한다.
      void queryClient.invalidateQueries({ queryKey: ["onboarding"] });
      void queryClient.invalidateQueries({ queryKey: ["menus"] });
    },
  });

  // 카드가 결과 문장으로 바뀌면 방금 누른 버튼이 사라진다. 초점을 그 문장으로 옮긴다.
  useEffect(() => {
    if (done) doneRef.current?.focus();
  }, [done]);

  if (skipped) return null;
  // 불러오는 중이거나 실패하면 아무것도 그리지 않는다 — 첫 화면을 오류로 맞이하게 하지 않는다.
  if (!statusQuery.isSuccess) return null;
  if (!statusQuery.data.needed && !done) return null;

  if (done) {
    return (
      <section className="card onboarding" aria-labelledby={headingId}>
        <h2 id={headingId} className="sr-only">첫 설정</h2>
        <p ref={doneRef} tabIndex={-1} role="status">
          {excluded.size > 0
            ? `${excluded.size}개를 뺐어요. 이제 뽑기를 눌러보세요!`
            : "좋아요, 바로 뽑아볼까요?"}{" "}
          나중에 <strong>내 메뉴</strong>에서 언제든 바꿀 수 있어요.
        </p>
      </section>
    );
  }

  const menus = statusQuery.data.menus;

  const toggle = (id: number) => {
    setExcluded((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const finish = () => {
    if (save.isPending) return;
    // 뺀 것이 없으면 보낼 것도 없다. 빈 목록을 보내면 서버가 400으로 거절한다(@NotEmpty).
    if (excluded.size === 0) {
      setDone(true);
      return;
    }
    save.mutate([...excluded]);
  };

  return (
    <section className="card onboarding" aria-labelledby={headingId}>
      <h2 id={headingId}>안 드시는 것부터 빼 볼까요?</h2>
      <p className="settings-desc">
        가입하면 기본 메뉴 {menus.length}개가 들어와 있어요. 평소 안 먹는 것을 눌러서 빼면 첫
        추천부터 취향이 반영돼요. 없으면 그냥 넘어가도 괜찮아요.
      </p>

      {save.isError && <p className="error" role="alert">{apiErrorMessage(save.error)}</p>}

      <ul className="chip-row" role="list">
        {menus.map((menu) => (
          <li key={menu.id}>
            <button
              type="button"
              {...chipToggle(excluded.has(menu.id))}
              aria-label={`${menu.name}${excluded.has(menu.id) ? ", 뺌" : ""}`}
              onClick={() => toggle(menu.id)}
            >
              {menu.name}
            </button>
          </li>
        ))}
      </ul>

      <div className="card-actions">
        <button
          type="button"
          aria-busy={save.isPending}
          aria-disabled={save.isPending || undefined}
          onClick={finish}
        >
          {save.isPending ? "저장 중…" : excluded.size > 0 ? `${excluded.size}개 빼고 시작하기` : "그대로 시작하기"}
        </button>
        <button
          type="button"
          onClick={() => {
            skipOnboarding();
            setSkipped(true);
          }}
        >
          다음에 할게요
        </button>
      </div>
    </section>
  );
}
