-- =============================================
-- 낙관적 락 버전 컬럼 (코드 리뷰 2026-08-21, issue #87)
--
-- 지금은 같은 행을 두 요청이 함께 고치면 나중 트랜잭션이 앞선 변경을 통째로 덮어쓴다.
-- 개인 데이터라 남의 변경을 잃는 일은 없지만, 자기 자신과는 충분히 겹친다 — 탭 두 개,
-- 폰과 PC, 또는 메뉴 편집(PUT)과 가중치 일괄 조정(PATCH /menus/weights)처럼 같은 행을
-- 건드리는 서로 다른 화면. 덮어쓴 쪽도 덮인 쪽도 성공 응답을 받으므로 아무도 모른다.
--
-- 버전 컬럼이 붙으면 UPDATE에 WHERE version = ? 가 함께 나가고, 0행이 갱신되면 Hibernate가
-- 예외를 던진다. "조용히 사라진 변경"이 "저장하지 못했다는 응답"으로 바뀐다.
--
-- 대상은 세 테이블이다. 기준은 "사용자가 자기 자신과 겹칠 수 있는 read-modify-write 전체
-- 교체 경로가 있는가":
--   menus            PUT /menus/{id} (전체 교체) + PATCH /menus/{id}/exclude
--                    + PATCH /menus/weights (한 트랜잭션에서 여러 행 read-modify-write)
--   restaurants      PUT /restaurants/{id} (전체 교체). soft delete 후 재등록 시 restore()가
--                    같은 행을 되살리며 필드를 덮어쓰는 경로와도 만난다.
--   menu_restaurants PUT /menus/{menuId}/restaurants/{restaurantId} — rating과 memo를 함께
--                    교체하므로 별점만 고친 탭과 메모만 고친 탭이 서로를 지운다.
--
-- 넣지 않는 테이블과 그 이유:
--   histories                 쓰기가 markVisited 하나이고 isVisited=true 방향의 단조 전이다.
--                             겹쳐도 잃을 앞선 변경이 없다. (이 테이블만 created_at/updated_at
--                             컬럼조차 없어 스키마를 예외적으로 늘리게 되는 점도 있다.)
--   tags,
--   history_filter_conditions 수정 엔드포인트 자체가 없다(생성·삭제뿐).
--   users, auth_providers     사용자 대면 PUT/PATCH로 전체를 교체하는 경로가 없다. 인증 흐름
--                             내부의 변경은 이미 AuthService.resolveUserWithConflictRetry가
--                             유니크 제약 + 재시도로 다룬다.
--
-- DEFAULT 0: 이미 있는 행에도 값이 필요하다. Hibernate는 0을 정상적인 시작 버전으로 읽으므로
-- 기존 데이터가 첫 수정에서 곧바로 1이 된다 — 마이그레이션 직후 한 번은 아무도 충돌하지 않는다.
--
-- ALGORITHM / LOCK: docs/DecisionLog.md D-031에 따라 명시한다. 이 선언은 "이렇게 해 달라"가
-- 아니라 "이렇게 못 하면 실패하라"는 안전장치다 — 조용히 COPY로 강등돼 테이블을 통째로
-- 복사하며 DML을 막는 사고를 마이그레이션 단위로 차단한다.
-- ADD COLUMN은 MySQL 8에서 INSTANT(메타데이터만 고치고 끝)가 가능하지만, INSTANT는 LOCK 절과
-- 함께 쓸 수 없어 "락을 잡지 않는다"는 보장을 같은 문장에 적을 수 없다. 세 테이블 모두
-- 개인 데이터라 행 수가 작고, INPLACE의 비용은 그 작은 테이블을 한 번 재구성하는 것뿐이다 —
-- 더 빠른 알고리즘보다 D-031이 요구하는 형태(INPLACE + LOCK=NONE)를 그대로 지키는 쪽을 택했다.
-- =============================================

-- 1. menus
ALTER TABLE menus
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ALGORITHM = INPLACE,
    LOCK = NONE;

-- 2. restaurants
ALTER TABLE restaurants
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ALGORITHM = INPLACE,
    LOCK = NONE;

-- 3. menu_restaurants
ALTER TABLE menu_restaurants
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ALGORITHM = INPLACE,
    LOCK = NONE;
