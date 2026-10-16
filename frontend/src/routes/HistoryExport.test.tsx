import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../test/renderWithProviders";
import { fetchHistoryCsv } from "../api/history";
import HistoryExport from "./HistoryExport";
import { useState } from "react";
import { beforeEach, afterEach, it, expect, vi } from "vitest";

vi.mock("../api/history", () => ({ fetchHistoryCsv: vi.fn() }));
const fetchCsvMock = vi.mocked(fetchHistoryCsv);
const OriginalURL = URL;
const createUrl = vi.fn();
const revokeUrl = vi.fn();
const clicks: { href: string; filename: string }[] = [];

beforeEach(() => {
  fetchCsvMock.mockReset(); createUrl.mockReset(); revokeUrl.mockReset(); clicks.length = 0;
  createUrl.mockReturnValue("blob:http://localhost/history-export");
  vi.stubGlobal("URL", class extends OriginalURL {
    static createObjectURL = createUrl;
    static revokeObjectURL = revokeUrl;
  });
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function(this: HTMLAnchorElement) {
    clicks.push({ href: this.href, filename: this.download });
  });
});
afterEach(async () => {
  if (clicks.length) await waitFor(() => expect(revokeUrl).toHaveBeenCalledWith("blob:http://localhost/history-export"), { timeout: 2000 });
  vi.restoreAllMocks(); vi.unstubAllGlobals();
});

it("목록 페이지와 무관하게 적용한 조건으로 파일을 내려받는다", async () => {
  const user = userEvent.setup();
  const blob = new Blob(["CSV"]);
  fetchCsvMock.mockResolvedValue(blob);
  renderWithProviders(<HistoryExport days={30} visited keyword="국수" />);
  await user.click(screen.getByRole("button", { name: "CSV 내려받기" }));
  await waitFor(() => expect(fetchCsvMock).toHaveBeenCalledWith(30, true, "국수"));
  expect(createUrl).toHaveBeenCalledWith(blob);
  expect(clicks).toEqual([{ href: "blob:http://localhost/history-export", filename: "menupick-history.csv" }]);
  expect(screen.getByRole("status")).toHaveTextContent("최근 30일·방문·검색어 '국수'");
  expect(document.querySelector("a[download]")).toBeNull();
});

it("빈 검색어는 생략하고 실패하면 다운로드 없이 다시 시도한다", async () => {
  const user = userEvent.setup();
  fetchCsvMock.mockRejectedValueOnce(new Error("기간이나 검색 조건을 좁혀주세요.")).mockResolvedValue(new Blob(["CSV"]));
  renderWithProviders(<HistoryExport days={7} keyword="" />);
  await user.click(screen.getByRole("button", { name: "CSV 내려받기" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("조건을 좁혀주세요");
  expect(createUrl).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "CSV 내려받기" }));
  await waitFor(() => expect(clicks).toHaveLength(1));
  expect(fetchCsvMock).toHaveBeenLastCalledWith(7, undefined, undefined);
});

it("준비 중 재클릭은 요청을 추가하지 않는다", async () => {
  const user = userEvent.setup();
  fetchCsvMock.mockReturnValue(new Promise<Blob>(() => {}));
  renderWithProviders(<HistoryExport days={7} visited={false} keyword="" />);
  await user.click(screen.getByRole("button", { name: "CSV 내려받기" }));
  await user.click(screen.getByRole("button", { name: "파일 준비 중…" }));
  expect(fetchCsvMock).toHaveBeenCalledTimes(1);
  expect(screen.getByRole("button")).toHaveAttribute("aria-busy", "true");
});

it("대기 중 필터를 바꿔도 요청 시점 조건으로 결과를 안내한다", async () => {
  const user = userEvent.setup();
  let resolve!: (blob: Blob) => void;
  fetchCsvMock.mockReturnValue(new Promise<Blob>((done) => { resolve = done; }));
  function ChangingFilters() {
    const [changed, setChanged] = useState(false);
    return <><button onClick={() => setChanged(true)}>조건 바꾸기</button>
      <HistoryExport days={changed ? 3650 : 7} visited={changed} keyword={changed ? "찌개" : "국수"} /></>;
  }
  renderWithProviders(<ChangingFilters />);
  await user.click(screen.getByRole("button", { name: "CSV 내려받기" }));
  await user.click(screen.getByRole("button", { name: "조건 바꾸기" }));
  await act(async () => { resolve(new Blob(["CSV"])); });
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("최근 7일·미방문·검색어 '국수'"));
  expect(fetchCsvMock).toHaveBeenCalledWith(7, false, "국수");
});
