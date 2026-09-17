import { useEffect, useRef, useState } from "react";
import { sharePick, type ShareOutcome } from "./sharePick";

/**
 * 뽑은 결과를 공유한다. 왜 공유이고 어떤 경로로 나가는지는 {@code sharePick.ts}에 있다.
 *
 * <p>여기서는 그 결과를 화면에 어떻게 말하는지만 다룬다. 세 가지가 서로 다른 말이 되어야 한다 —
 * 공유 앱으로 넘어간 것, 클립보드에 담긴 것, 아무것도 못 해서 직접 복사해야 하는 것.
 */
export default function SharePickButton({ menuName }: { menuName: string }) {
  const [outcome, setOutcome] = useState<ShareOutcome | null>(null);
  const [sharing, setSharing] = useState(false);
  const manualRef = useRef<HTMLTextAreaElement>(null);

  // 직접 복사해야 하는 경우에는 문구를 미리 선택해 둔다. Ctrl+C 한 번으로 끝나야 한다.
  useEffect(() => {
    if (outcome?.kind === "manual") {
      manualRef.current?.focus();
      manualRef.current?.select();
    }
  }, [outcome]);

  return (
    <>
      <button
        type="button"
        aria-busy={sharing}
        aria-disabled={sharing || undefined}
        onClick={() => {
          // aria-disabled는 표시일 뿐이다. 공유 시트가 두 번 뜨지 않게 여기서 막는다.
          if (sharing) return;
          setSharing(true);
          void sharePick(menuName, window.location.origin)
            .then(setOutcome)
            .finally(() => setSharing(false));
        }}
      >
        📤 공유하기
      </button>

      {/* 공유 시트로 넘어갔거나 사용자가 닫은 경우에는 아무 말도 하지 않는다 —
          화면 밖에서 이미 벌어진 일이고, 취소는 실패가 아니다. */}
      {outcome?.kind === "copied" && (
        <p className="card-muted-hint" role="status">
          공유 문구를 복사했어요. 붙여넣기로 보내세요.
        </p>
      )}

      {outcome?.kind === "manual" && (
        <div className="share-manual" role="group" aria-label="공유 문구 직접 복사">
          <p className="card-muted-hint">
            이 브라우저에서는 자동 복사가 막혀 있어요. 아래 문구를 복사해서 보내주세요.
          </p>
          <textarea ref={manualRef} readOnly rows={2} value={outcome.text} aria-label="공유 문구" />
        </div>
      )}
    </>
  );
}
