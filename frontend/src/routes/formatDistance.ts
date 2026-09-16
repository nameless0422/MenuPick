/** 미터를 "350m" / "1.2km" / "2km"로. 픽 결과의 연결 식당과 주변 검색 결과가 같은 표기를 쓴다. */
export function formatDistance(meters: number) {
  if (meters >= 1000) {
    const km = meters / 1000;
    return `${Number.isInteger(km) ? km : km.toFixed(1)}km`;
  }
  return `${Math.round(meters)}m`;
}
