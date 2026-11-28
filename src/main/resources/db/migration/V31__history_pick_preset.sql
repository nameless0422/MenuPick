-- =============================================
-- 픽 기록이 어느 빠른 픽에서 나왔는가 (D-095)
--
-- 끼니(아침·점심·저녁·간식)별로 자주 쓴 빠른 픽을 목록 맨 앞에 제안하는 데만 쓴다.
-- NULL이면 수동 픽·직접 기록·함께 뽑기다. 기존 기록은 전부 NULL로 시작한다.
--
-- 외래 키를 두지 않는다. 빠른 픽을 지워도 기록은 남아야 하고(ON DELETE SET NULL이면 되지만),
-- histories에 FK를 더하면 INPLACE로 못 해 테이블 복사가 일어난다. id는 AUTO_INCREMENT라
-- 재사용되지 않으므로 지워진 빠른 픽의 id는 어떤 현재 빠른 픽과도 맞지 않는다 — 제안 계산은
-- 현재 목록에 있는 id만 센다.
-- =============================================

ALTER TABLE histories
    ADD COLUMN pick_preset_id BIGINT NULL,
    ALGORITHM=INPLACE, LOCK=NONE;
