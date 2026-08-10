-- =============================================
-- 인덱스 정리 (GitHub issue #16)
-- (V2는 rating 컬럼 타입 정렬에 이미 사용 중이라 V3으로 매긴다)
-- 실제 쿼리와 맞지 않는 인덱스를 걷어내고, 풀스캔/filesort가 나는 경로에 인덱스를 추가한다.
-- MySQL 8은 DROP INDEX IF EXISTS를 지원하지 않는다. 아래 인덱스는 모두 V1에서 생성되므로
-- 조건 없이 DROP한다.
-- =============================================

-- 1. users.deleted_at
--    WithdrawnUserCleanupScheduler가 매일 findAllByDeletedAtBefore(cutoff)로 조회한다.
--    인덱스가 없어 users 전체를 훑는다. deleted_at은 대부분 NULL이라 선택도가 매우 높다.
ALTER TABLE users
    ADD INDEX idx_users_deleted (deleted_at);

-- 2. histories 커서 페이지네이션
--    HistoryService는 (user_id, recommended_at > ?) 조건에 id DESC 정렬 + LIMIT으로 조회한다.
--    기존 idx_histories_user_time은 정렬 키(recommended_at)와 실제 정렬 기준(id)이 달라
--    filesort가 붙는다. 정렬 키를 바꾸면 API 커서 계약이 흔들리므로, 정렬은 그대로 두고
--    (user_id, id DESC) 인덱스로 정렬을 인덱스에 맡긴다.
ALTER TABLE histories
    ADD INDEX idx_histories_user_id (user_id, id DESC);

-- 3. history_filter_conditions 중복 인덱스 제거
--    idx_hfc_history_id(history_id)는 idx_hfc_type_value(history_id, filter_type)의
--    좌측 접두사라 완전히 중복이다. FK(fk_hfc_history)가 요구하는 인덱스도
--    idx_hfc_type_value가 대신 만족한다.
ALTER TABLE history_filter_conditions
    DROP INDEX idx_hfc_history_id;

-- 4. menus 인덱스 정리
--    - idx_menus_name_search(user_id, name): 이름 검색 쿼리가 없다(메뉴 검색 API 미구현).
--    - idx_menus_user_excluded(user_id, is_excluded, id, name): 커버링을 노린 구성이지만
--      실제 픽 후보 조회는 findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull —
--      deleted_at 조건이 인덱스에 없어 걸러내지 못한다. deleted_at을 포함해 재생성한다.
--    FK(fk_menus_user)용 인덱스는 idx_menus_user_deleted(user_id, deleted_at)가 유지한다.
ALTER TABLE menus
    DROP INDEX idx_menus_name_search;

ALTER TABLE menus
    DROP INDEX idx_menus_user_excluded;

ALTER TABLE menus
    ADD INDEX idx_menus_user_excluded (user_id, is_excluded, deleted_at);
