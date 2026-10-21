import { useId, useState, type ReactNode, type Ref } from "react";
import { useCompactLayout } from "../a11y/useCompactLayout";

/** 데스크톱에서는 펼쳐 두고 모바일에서는 보조 작업을 선택해서 연다. */
export default function MobileSection({ title, label, children, triggerRef, className = "" }: {
  title: string;
  label?: string;
  children: ReactNode;
  triggerRef?: Ref<HTMLButtonElement>;
  className?: string;
}) {
  const compact = useCompactLayout();
  const [expanded, setExpanded] = useState(false);
  const id = useId();
  return (
    <div className={`mobile-section ${className}`}>
      {compact && <button ref={triggerRef} type="button" className="mobile-section-toggle"
        aria-label={label}
        aria-expanded={expanded} aria-controls={id}
        onClick={() => setExpanded(!expanded)}>
        {title}<span aria-hidden="true">{expanded ? "−" : "+"}</span>
      </button>}
      <div id={id} hidden={compact && !expanded}>{children}</div>
    </div>
  );
}
