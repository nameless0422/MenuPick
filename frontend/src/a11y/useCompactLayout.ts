import { useSyncExternalStore } from "react";

const QUERY = "(max-width: 760px)";
const snapshot = () => window.matchMedia?.(QUERY).matches ?? false;
const subscribe = (onChange: () => void) => {
  const media = window.matchMedia?.(QUERY);
  media?.addEventListener?.("change", onChange);
  return () => media?.removeEventListener?.("change", onChange);
};

export function useCompactLayout() {
  return useSyncExternalStore(subscribe, snapshot, () => false);
}
