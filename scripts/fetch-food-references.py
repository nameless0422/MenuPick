#!/usr/bin/env python3
"""
공공데이터포털의 "전국통합식품영양성분정보(음식)표준데이터"(data.go.kr/data/15100070)를 CSV로 받는다 (D-082).

포털의 CSV 버튼은 브라우저에서 JSON을 1만 행씩 받아 CSV로 엮는다. 이 스크립트가 같은 요청을 보낸다 —
로그인·인증키가 필요 없어서 서버에서 바로 받을 수 있다. 출력은 UTF-8(BOM) CSV이고 헤더는 한글
열 이름 그대로라 import-food-references.py가 그대로 읽는다.

    python3 scripts/fetch-food-references.py /tmp/food.csv

이용 조건: 데이터셋에 공공누리 유형 표시는 없다. 식약처가 업무상 작성·공표한 데이터라 자유 이용으로
보고, 앱의 기준 음식 검색 화면에 출처(식품의약품안전처 식품영양성분 데이터베이스)를 적어 둔다.

포털이 화면 구조를 바꾸면 깨질 수 있다. 그때는 브라우저에서 CSV 버튼으로 받아도 결과는 같다.
"""
import csv
import http.cookiejar
import json
import math
import sys
import urllib.parse
import urllib.request

PK = "15100070"
BASE = "https://www.data.go.kr"
PER_PAGE = 10000  # 포털 화면이 쓰는 값


def main():
    if len(sys.argv) != 2:
        sys.exit("사용법: fetch-food-references.py <저장할 CSV 경로>")
    out_path = sys.argv[1]

    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    opener.addheaders = [
        ("User-Agent", "Mozilla/5.0 (compatible; menupick-food-fetch)"),
        ("X-Requested-With", "XMLHttpRequest"),
        ("Referer", f"{BASE}/data/{PK}/standard.do"),
    ]

    def get_json(path, params):
        url = f"{BASE}{path}?" + urllib.parse.urlencode(params)
        with opener.open(url, timeout=120) as res:
            return json.loads(res.read())

    # 세션 쿠키를 먼저 받아야 다운로드 요청이 HTML 오류 페이지 대신 JSON을 준다.
    opener.open(f"{BASE}/data/{PK}/standard.do", timeout=60).read()
    header = get_json("/download/columList.json", {"pk": PK, "ext": "CSV"})

    columns = header["columList"]
    table = header["tableVO"]
    total = int(header["totalCount"])
    if total <= 0:
        sys.exit("ERROR: 포털이 행 수를 0으로 알려 왔다")

    rows = 0
    with open(out_path, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([c["columNm"] for c in columns])
        for page in range(1, math.ceil(total / PER_PAGE) + 1):
            params = [
                ("publicDataPk", PK),
                ("totalCount", total),
                ("svcTableNm", table["svcTableNm"]),
                ("perPage", PER_PAGE),
                ("page", page),
            ] + [("colNmList", name) for name in table["colNmList"]]
            data = get_json("/download/standard.json", params)
            for record in data:
                writer.writerow(["" if record.get(c["columCode"]) is None else record[c["columCode"]]
                                 for c in columns])
            rows += len(data)

    if rows != total:
        sys.exit(f"ERROR: {total}행 중 {rows}행만 받았다 — 다시 시도한다")
    print(f"{rows}행을 받았다 → {out_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
