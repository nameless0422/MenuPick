#!/usr/bin/env python3
"""
식약처 "전국통합식품영양성분정보(음식)표준데이터" CSV를 food_references에 넣는 SQL로 바꾼다 (D-082).

왜 SQL을 출력하는가: 데이터는 저장소에 넣지 않는다(이용 조건과 크기 때문에). 서버에서 받은 CSV를
이 스크립트에 통과시켜 mysql로 바로 흘려 넣는다. DB 드라이버가 필요 없고, 출력 파일을 눈으로
검토할 수도 있다.

    python3 scripts/import-food-references.py 음식DB.csv > /tmp/food.sql
    docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" menupick-mysql mysql -uroot menupick < /tmp/food.sql

규칙:
  - 코드(식품코드) 기준 upsert. 행은 지우지 않는다 — 메뉴가 food_reference_id로 가리키고 있다.
  - 원본은 "영양성분함량 기준량"(보통 100g) 기준이다. "식품중량"(1인분 무게)으로 1인분 값을 계산해 둔다.
    둘 중 하나라도 읽을 수 없으면 그 행은 건너뛴다 — 1인분 열량을 지어내지 않는다.
  - 끝에 연결된 메뉴의 열량·탄단지·식이섬유를 새 값으로 맞춘다(메뉴는 값을 복사해 두기 때문이다).
  - 건너뛴 행 수와 이유는 표준 오류로 알린다.
"""
import csv
import io
import re
import sys
from collections import Counter
from datetime import datetime, timedelta, timezone

BATCH = 500
AMOUNT = re.compile(r"^\s*([0-9]+(?:\.[0-9]+)?)\s*(g|ml|mL|ML|G)?\s*$")

# 헤더 이름이 판마다 조금씩 다르다("1인(회)분량 참고량" / "1회 섭취참고량"). 후보를 차례로 찾는다.
COLUMNS = {
    "code": ["식품코드"],
    "name": ["식품명"],
    "category": ["식품대분류명", "식품대분류", "대표식품명"],
    "base": ["영양성분함량 기준량", "영양성분함량기준량"],
    "kcal": ["에너지(kcal)", "에너지(㎉)"],
    "carbs": ["탄수화물(g)"],
    "protein": ["단백질(g)"],
    "fat": ["지방(g)"],
    "fiber": ["식이섬유(g)", "총 식이섬유(g)", "총식이섬유(g)"],
    "sugars": ["당류(g)", "총당류(g)", "총 당류(g)"],
    "sodium": ["나트륨(mg)", "나트륨(㎎)"],
    "weight": ["식품중량"],
    "date": ["데이터생성일자", "데이터기준일자"],
}


def read_text(path):
    raw = open(path, "rb").read()
    for encoding in ("utf-8-sig", "cp949"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    sys.exit("ERROR: CSV 인코딩을 읽지 못했다 (UTF-8, CP949 모두 실패)")


def find_columns(header):
    found = {}
    for key, names in COLUMNS.items():
        for name in names:
            if name in header:
                found[key] = header.index(name)
                break
    missing = [key for key in ("code", "name", "base", "kcal", "weight") if key not in found]
    if missing:
        sys.exit("ERROR: 필요한 열이 없다: %s (헤더: %s)" % (", ".join(missing), ", ".join(header[:20])))
    return found


def number(value):
    if value is None:
        return None
    value = value.strip().replace(",", "")
    if value in ("", "-"):
        return None
    try:
        return float(value)
    except ValueError:
        return None


def amount(value):
    """'100g', '259ml', '300' → (숫자, 단위). 읽을 수 없으면 None."""
    if value is None:
        return None
    match = AMOUNT.match(value.replace(",", ""))
    if not match:
        return None
    unit = (match.group(2) or "g").lower()
    return float(match.group(1)), unit


def sql_string(value, limit):
    if value is None:
        return "NULL"
    value = value.strip()[:limit]
    if not value:
        return "NULL"
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def sql_number(value, digits=1):
    return "NULL" if value is None else ("%.*f" % (digits, value))


def sql_date(value):
    if not value:
        return "NULL"
    value = value.strip()
    for fmt in ("%Y-%m-%d", "%Y%m%d", "%Y.%m.%d"):
        try:
            return "'" + datetime.strptime(value, fmt).strftime("%Y-%m-%d") + "'"
        except ValueError:
            continue
    return "NULL"


def main(argv):
    if len(argv) != 2:
        sys.exit("사용법: import-food-references.py <음식DB.csv>")
    reader = csv.reader(io.StringIO(read_text(argv[1])))
    header = [cell.strip() for cell in next(reader)]
    col = find_columns(header)

    def cell(row, key):
        index = col.get(key)
        return row[index] if index is not None and index < len(row) else None

    # DB의 시각은 전부 시간대 없는 KST다(TimeConfig). 여기만 UTC로 적으면 9시간 어긋난다.
    now = datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M:%S")
    skipped = Counter()
    values = []
    seen = set()
    for row in reader:
        if not row or not any(c.strip() for c in row):
            continue
        code = (cell(row, "code") or "").strip()
        name = (cell(row, "name") or "").strip()
        if not code or not name or len(code) > 30:
            skipped["코드/이름 없음"] += 1
            continue
        if code in seen:
            skipped["코드 중복"] += 1
            continue
        base = amount(cell(row, "base"))
        weight = amount(cell(row, "weight"))
        kcal = number(cell(row, "kcal"))
        if base is None or base[0] <= 0 or weight is None or weight[0] <= 0:
            skipped["기준량/식품중량 읽을 수 없음"] += 1
            continue
        if kcal is None or kcal < 0:
            skipped["열량 없음"] += 1
            continue
        if base[1] != weight[1]:
            # g와 ml를 섞으면 밀도를 모른다. 지어내지 않는다.
            skipped["기준량과 식품중량 단위 다름"] += 1
            continue
        factor = weight[0] / base[0]
        per_serving = round(kcal * factor)
        if per_serving > 65535:
            skipped["열량 범위 밖"] += 1
            continue

        def scaled(key):
            v = number(cell(row, key))
            # DECIMAL(6,1)에 들어가지 않는 값은 원본 오류로 보고 모름으로 둔다.
            return None if v is None or v < 0 or v * factor >= 10000 else v * factor

        def scaled_mg(key):
            v = number(cell(row, key))
            # 나트륨은 mg 정수(INT). 1인분 100,000mg 이상은 원본 오류(단위 혼동)로 보고 모름으로 둔다.
            return None if v is None or v < 0 or v * factor >= 100000 else v * factor

        seen.add(code)
        values.append("(%s,%s,%s,%s,%d,%s,%s,%s,%s,%s,%s,%s,'%s')" % (
            sql_string(code, 30), sql_string(name, 100), sql_string(cell(row, "category"), 50),
            sql_number(weight[0]), per_serving,
            sql_number(scaled("carbs")), sql_number(scaled("protein")), sql_number(scaled("fat")),
            sql_number(scaled("fiber")), sql_number(scaled("sugars")), sql_number(scaled_mg("sodium"), 0),
            sql_date(cell(row, "date")), now))

    out = sys.stdout
    out.write("-- generated by scripts/import-food-references.py at %s KST\n" % now)
    out.write("SET NAMES utf8mb4;\nSTART TRANSACTION;\n")
    for i in range(0, len(values), BATCH):
        out.write("INSERT INTO food_references "
                  "(code,name,category,serving_grams,kcal_per_serving,carbs_g,protein_g,fat_g,fiber_g,"
                  "sugars_g,sodium_mg,source_date,imported_at) VALUES\n")
        out.write(",\n".join(values[i:i + BATCH]))
        out.write("\nON DUPLICATE KEY UPDATE name=VALUES(name), category=VALUES(category), "
                  "serving_grams=VALUES(serving_grams), kcal_per_serving=VALUES(kcal_per_serving), "
                  "carbs_g=VALUES(carbs_g), protein_g=VALUES(protein_g), fat_g=VALUES(fat_g), fiber_g=VALUES(fiber_g), "
                  "sugars_g=VALUES(sugars_g), sodium_mg=VALUES(sodium_mg), "
                  "source_date=VALUES(source_date), imported_at=VALUES(imported_at);\n")
    # 메뉴는 값을 복사해 둔다(D-082, D-084, D-085). 기준 값이 바뀌었으면 연결된 메뉴도 따라가게 한다.
    # <=>는 NULL끼리도 같다고 보는 비교다 — 바뀐 메뉴만 고쳐 버전·수정 시각을 괜히 흔들지 않는다.
    out.write("UPDATE menus m JOIN food_references f ON f.id = m.food_reference_id "
              "SET m.kcal_per_serving = f.kcal_per_serving, m.carbs_g = f.carbs_g, m.protein_g = f.protein_g, "
              "m.fat_g = f.fat_g, m.fiber_g = f.fiber_g, m.sugars_g = f.sugars_g, m.sodium_mg = f.sodium_mg "
              "WHERE NOT (m.kcal_per_serving <=> f.kcal_per_serving AND m.carbs_g <=> f.carbs_g "
              "AND m.protein_g <=> f.protein_g AND m.fat_g <=> f.fat_g AND m.fiber_g <=> f.fiber_g "
              "AND m.sugars_g <=> f.sugars_g AND m.sodium_mg <=> f.sodium_mg);\n")
    out.write("COMMIT;\n")

    sys.stderr.write("가져올 행 %d개\n" % len(values))
    for reason, count in skipped.most_common():
        sys.stderr.write("  건너뜀 %d개: %s\n" % (count, reason))
    if not values:
        sys.exit("ERROR: 가져올 행이 없다")


if __name__ == "__main__":
    main(sys.argv)
