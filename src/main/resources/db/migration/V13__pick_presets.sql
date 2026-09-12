-- =============================================
-- 상황별 빠른 픽 — 저장형 프리셋 (docs/PickPresetDesign.md)
--
-- 사용자가 자주 쓰는 픽 조건에 이름을 붙여 저장하고 다시 실행한다(예: "회사 점심", "혼밥").
-- 매번 같은 카테고리·태그·거리를 다시 고르는 반복 입력을 줄이는 것이 목적이다.
-- 사용자 조사로 검증된 수요가 아니라 제품 가설이다.
--
-- 저장하지 않는 것이 두 가지 있고 둘 다 의도적이다.
--
--   1. 좌표·장소. 거리 조건은 "몇 m 이내"만 저장하고 기준점은 실행할 때마다 브라우저에서
--      새로 받는다. 프리셋에 좌표를 굳히면 회사에서 만든 "회사 점심"을 집에서 실행했을 때
--      조용히 회사 주변을 뽑는다. PrivacyReview.md의 "좌표 비영속" 약속과도 맞물린다.
--
--   2. 기본 제외 태그(user_default_excluded_tags). 저장 시점에 복사하면 나중에 사용자가
--      알레르기 태그를 추가해도 옛 프리셋은 그걸 모른 채 계속 뽑는다. 실행할 때마다 최신
--      값을 합친다. 그래서 이 테이블에는 "추가 제외"만 들어간다(filter_type='EXCLUDE').
-- =============================================

CREATE TABLE pick_presets (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    name         VARCHAR(30)  NOT NULL,
    -- 참조하던 태그가 삭제되면 켜진다. 켜져 있는 동안 실행을 막는다 — 조건이 조용히
    -- 넓어진 채로 추천하지 않기 위해서다(설계 7절). 사용자가 편집 화면에서 전체 조건을
    -- 다시 저장하며 명시적으로 승인해야 꺼진다.
    needs_review TINYINT(1)   NOT NULL DEFAULT 0,
    -- 낙관적 락. 수정·삭제·실행이 모두 이 값을 확인한다(V9의 다른 테이블과 같은 관례).
    version      BIGINT       NOT NULL DEFAULT 0,
    -- NULL이면 거리 조건 없음. 값이 있으면 300/500/1000/2000만 허용한다(애플리케이션 검증).
    max_distance INT          NULL,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 한 사용자 안에서 이름은 유일하다. 비교는 컬럼 collation을 따른다 —
    -- V7 이후 스키마는 utf8mb4_0900_ai_ci이므로 대소문자·악센트를 구분하지 않는다.
    -- ("회사 점심"과 "회사 점심"은 같은 이름이고, 그 사실을 API 문서와 테스트가 고정한다.)
    CONSTRAINT uq_pick_presets_user_name UNIQUE (user_id, name),
    CONSTRAINT fk_pick_presets_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 목록 조회는 항상 사용자 기준이다. UNIQUE(user_id, name)의 왼쪽 접두사가 그 역할을 하므로
-- user_id 단독 인덱스를 따로 만들지 않는다.

CREATE TABLE pick_preset_categories (
    preset_id BIGINT      NOT NULL,
    category  VARCHAR(20) NOT NULL,
    PRIMARY KEY (preset_id, category),
    CONSTRAINT fk_pick_preset_categories_preset
        FOREIGN KEY (preset_id) REFERENCES pick_presets(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pick_preset_tags (
    preset_id   BIGINT      NOT NULL,
    tag_id      BIGINT      NOT NULL,
    -- INCLUDE | EXCLUDE. EXCLUDE는 "추가 제외"만 뜻한다 — 기본 제외는 여기 들어오지 않는다.
    filter_type VARCHAR(10) NOT NULL,
    PRIMARY KEY (preset_id, tag_id, filter_type),
    CONSTRAINT fk_pick_preset_tags_preset
        FOREIGN KEY (preset_id) REFERENCES pick_presets(id) ON DELETE CASCADE,
    -- 태그가 지워지면 이 행도 함께 사라진다. 부모 프리셋은 남고 needs_review가 켜진다
    -- (TagService.deleteTag가 삭제 전에 표시한다) — 조건이 줄어든 채로 도는 것을 막는다.
    CONSTRAINT fk_pick_preset_tags_tag
        FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 태그 삭제 시 "이 태그를 참조하는 프리셋"을 찾아 needs_review를 켜야 한다.
-- PK의 왼쪽 접두사가 preset_id라 tag_id 단독 조회는 인덱스를 못 타므로 따로 만든다.
CREATE INDEX idx_pick_preset_tags_tag ON pick_preset_tags (tag_id);
