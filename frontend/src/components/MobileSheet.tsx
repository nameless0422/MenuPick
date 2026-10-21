import { useId, useLayoutEffect, useRef, type ReactNode } from "react";

/** 네이티브 dialog로 배경 조작과 초점 이탈을 막고, 닫으면 열었던 버튼으로 돌아간다. */
export default function MobileSheet({ open, title, onDismiss, children, footer }: {
  open: boolean;
  title: string;
  onDismiss: () => void;
  children: ReactNode;
  footer?: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useLayoutEffect(() => {
    if (!open || !ref.current) return;
    const dialog = ref.current;
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const overflow = document.body.style.overflow;
    dialog.showModal();
    document.body.style.overflow = "hidden";
    const viewport = window.visualViewport;
    const fitViewport = () => {
      if (!viewport) return;
      dialog.style.setProperty("--sheet-height", `${viewport.height}px`);
      dialog.style.setProperty("--sheet-bottom", `${Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop)}px`);
    };
    fitViewport();
    viewport?.addEventListener("resize", fitViewport);
    viewport?.addEventListener("scroll", fitViewport);
    return () => {
      viewport?.removeEventListener("resize", fitViewport);
      viewport?.removeEventListener("scroll", fitViewport);
      dialog.close();
      document.body.style.overflow = overflow;
      if (trigger?.isConnected) trigger.focus({ preventScroll: true });
    };
  }, [open]);

  if (!open) return null;
  return (
    // 네이티브 dialog의 배경 클릭만 닫기로 처리한다. 키보드는 onCancel의 Escape와 닫기 버튼을 사용한다.
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <dialog ref={ref} className="mobile-sheet" aria-labelledby={titleId}
      onKeyDown={(event) => {
        if (event.key !== "Tab") return;
        const controls = [...event.currentTarget.querySelectorAll<HTMLElement>(
          "button, input, select, textarea, a[href], [tabindex]",
        )].filter((element) => element.tabIndex >= 0 && !element.matches(":disabled") && element.getClientRects().length > 0);
        const first = controls[0];
        const last = controls.at(-1);
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault(); last?.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault(); first?.focus();
        }
      }}
      onCancel={(event) => { event.preventDefault(); onDismiss(); }}
      onClick={(event) => {
        if (event.target !== event.currentTarget) return;
        const box = event.currentTarget.getBoundingClientRect();
        if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) onDismiss();
      }}>
      <header className="mobile-sheet-header">
        <h2 id={titleId}>{title}</h2>
        <button type="button" aria-label="닫기" onClick={onDismiss}>✕</button>
      </header>
      <div className="mobile-sheet-content">{children}</div>
      {footer && <footer className="mobile-sheet-footer">{footer}</footer>}
    </dialog>
  );
}
