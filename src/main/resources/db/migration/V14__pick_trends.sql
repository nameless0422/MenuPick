-- 집단 선택·방문 통계의 원자적 스냅샷. 이 migration은 아직 배포되지 않아 함께 확정한다.
CREATE TABLE pick_trend_snapshots (
    id BIGINT NOT NULL, computed_at DATETIME(6) NULL,
    window_days INT NOT NULL, min_users INT NOT NULL, max_labels INT NOT NULL,
    PRIMARY KEY (id), CONSTRAINT chk_pick_trend_snapshot_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO pick_trend_snapshots (id, computed_at, window_days, min_users, max_labels)
VALUES (1, NULL, 7, 5, 10);

CREATE TABLE pick_trends (
    id BIGINT NOT NULL AUTO_INCREMENT, dimension VARCHAR(20) NOT NULL,
    label VARCHAR(100) NOT NULL, user_count INT NOT NULL, rank_order INT NOT NULL,
    snapshot_id BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT uq_pick_trends_dimension_label UNIQUE (dimension, label),
    CONSTRAINT fk_pick_trends_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES pick_trend_snapshots(id) ON DELETE RESTRICT,
    INDEX idx_pick_trends_dimension_rank (dimension, rank_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
