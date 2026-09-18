-- =============================================
-- 여럿이 같이 뽑기 — 방
--
-- 호스트가 자기 메뉴로 방을 만들고 링크를 공유하면, 링크를 받은 사람이 로그인 없이 들어와
-- "이건 빼주세요"를 누르고, 남은 것 중에서 하나를 뽑는다.
--
-- 왜 비회원도 참여하는가
--   점심 메뉴를 정하는 자리에 있는 사람 전원이 이 서비스에 가입해 있을 리 없다. 가입을
--   요구하면 방을 만들 이유 자체가 사라진다. 대신 참여자는 "방 링크를 아는 사람"으로만
--   제한되고(code는 추측 불가), 남의 데이터는 보이지 않는다 — 방에 담기는 것은 호스트가
--   스스로 공유한 자기 메뉴 이름뿐이다.
--
-- 왜 메뉴를 스냅샷으로 복사하는가
--   방이 열려 있는 동안 호스트가 메뉴 이름을 고치거나 지울 수 있다. 참여자가 보던 목록이
--   중간에 바뀌면 방금 누른 제외가 다른 메뉴에 붙는다. 그래서 방을 만드는 순간의 이름과
--   가중치를 복사해 두고, 그 사본으로만 뽑는다.
-- =============================================

CREATE TABLE pick_rooms (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    -- 공유 링크에 들어가는 값. 랜덤이라 추측할 수 없고, 이것이 곧 방의 입장 자격이다.
    code          VARCHAR(32)  NOT NULL,
    host_user_id  BIGINT       NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    -- 점심 한 끼를 정하는 자리라 오래 살아 있을 이유가 없다. 지난 방은 조회에서 404가 되고
    -- 정리 스케줄러가 지운다.
    expires_at    DATETIME(6)  NOT NULL,
    -- 뽑기가 끝난 시각. 한 번 정해지면 바뀌지 않는다 — 같이 정한 결과를 누가 다시 굴려
    -- 덮어쓸 수 있으면 "같이 정했다"가 성립하지 않는다.
    decided_at    DATETIME(6)  NULL,
    decided_room_menu_id BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_pick_rooms_code UNIQUE (code),
    CONSTRAINT fk_pick_rooms_user FOREIGN KEY (host_user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    -- 만료 정리가 훑는 축.
    INDEX idx_pick_rooms_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pick_room_menus (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    room_id   BIGINT       NOT NULL,
    -- 원본 메뉴. 뽑힌 뒤 호스트 히스토리에 기록할 때 쓴다. 메뉴가 지워지면 함께 사라지지만
    -- 방 자체는 아래 name 사본으로 계속 보인다.
    menu_id   BIGINT       NULL,
    -- 방을 만든 순간의 이름·가중치 사본. 근거는 파일 머리말.
    name      VARCHAR(100) NOT NULL,
    weight    INT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pick_room_menus_room FOREIGN KEY (room_id)
        REFERENCES pick_rooms(id) ON DELETE CASCADE,
    CONSTRAINT fk_pick_room_menus_menu FOREIGN KEY (menu_id)
        REFERENCES menus(id) ON DELETE SET NULL,
    -- 같은 메뉴가 한 방에 두 번 들어가면 뽑힐 확률이 두 배가 된다.
    CONSTRAINT uq_pick_room_menus UNIQUE (room_id, menu_id),
    INDEX idx_pick_room_menus_room (room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE pick_rooms
    ADD CONSTRAINT fk_pick_rooms_decided FOREIGN KEY (decided_room_menu_id)
        REFERENCES pick_room_menus(id) ON DELETE SET NULL;

CREATE TABLE pick_room_vetoes (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    room_menu_id BIGINT       NOT NULL,
    /*
     * 참가자 식별자. 로그인하지 않은 사람도 참여하므로 계정이 아니라 브라우저가 만든
     * 랜덤 값이다. **방마다 새로 만든다** — 하나를 여러 방에서 재사용하면 "같은 사람이
     * 이 방들에 다 있었다"가 DB에 남는다. 이 값에는 이름·기기 정보가 들어가지 않는다.
     */
    participant  VARCHAR(64)  NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pick_room_vetoes_menu FOREIGN KEY (room_menu_id)
        REFERENCES pick_room_menus(id) ON DELETE CASCADE,
    -- 한 사람이 같은 메뉴를 두 번 빼도 한 번이다. 세는 단위는 "제외한 사람 수"다.
    CONSTRAINT uq_pick_room_vetoes UNIQUE (room_menu_id, participant)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
