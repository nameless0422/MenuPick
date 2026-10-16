import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { fetchHistoryCsv } from "../api/history";
import { apiErrorMessage } from "../api/http";

interface Filters { days: number; visited?: boolean; keyword: string; }

export default function HistoryExport(filters: Filters) {
  const [notice, setNotice] = useState("");
  const download = useMutation({
    mutationFn: (selected: Filters) => fetchHistoryCsv(selected.days, selected.visited, selected.keyword || undefined),
    onSuccess: (blob, selected) => {
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      try {
        link.href = url;
        link.download = "menupick-history.csv";
        document.body.append(link);
        link.click();
      } finally {
        link.remove();
        // 브라우저가 다운로드를 시작할 시간을 준 뒤 메모리의 파일 URL을 해제한다.
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      }
      const period = selected.days === 3650 ? "전체 기간" : `최근 ${selected.days}일`;
      const state = selected.visited === undefined ? "모든 방문 상태" : selected.visited ? "방문" : "미방문";
      setNotice(`${period}·${state}${selected.keyword ? `·검색어 '${selected.keyword}'` : ""} 조건의 CSV 다운로드를 시작했습니다.`);
    },
  });
  return <section aria-label="픽 기록 내려받기" className="card">
    <h2>픽 기록 내려받기</h2>
    <p>현재 기간·방문 상태·검색 조건에 맞는 기록을 최대 1,000건까지 CSV로 저장합니다. 더 보기 전의 기록도 포함합니다.</p>
    <button type="button" aria-busy={download.isPending} aria-disabled={download.isPending || undefined}
      onClick={() => {
        if (download.isPending) return;
        setNotice("");
        download.mutate({ ...filters });
      }}>{download.isPending ? "파일 준비 중…" : "CSV 내려받기"}</button>
    {download.isError && <p className="error" role="alert">{apiErrorMessage(download.error)}</p>}
    <p role="status" aria-label="내려받기 상태">{notice}</p>
  </section>;
}
