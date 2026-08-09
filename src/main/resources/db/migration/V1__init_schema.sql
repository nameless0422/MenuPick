-- =============================================
-- MenuPick 초기 스키마 (Planning.md 3장 기반)
-- =============================================

-- 1. users
CREATE TABLE users (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    email      VARCHAR(255) NULL,
    nickname   VARCHAR(50)  NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. auth_providers
CREATE TABLE auth_providers (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    provider   VARCHAR(20)  NOT NULL,
    social_id  VARCHAR(100) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_auth_provider_social (provider, social_id),
    INDEX idx_auth_user_id (user_id),
    CONSTRAINT fk_auth_providers_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. menus
CREATE TABLE menus (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    memo        TEXT         NULL,
    is_excluded TINYINT(1)   NOT NULL DEFAULT 0,
    weight      INT          NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  DATETIME     NULL,
    PRIMARY KEY (id),
    INDEX idx_menus_user_deleted (user_id, deleted_at),
    INDEX idx_menus_user_excluded (user_id, is_excluded, id, name),
    INDEX idx_menus_name_search (user_id, name),
    CONSTRAINT fk_menus_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. menu_categories
CREATE TABLE menu_categories (
    menu_id  BIGINT      NOT NULL,
    category VARCHAR(20) NOT NULL,
    PRIMARY KEY (menu_id, category),
    INDEX idx_menu_categories_cat (category),
    CONSTRAINT fk_menu_categories_menu FOREIGN KEY (menu_id) REFERENCES menus (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. tags
CREATE TABLE tags (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    name       VARCHAR(50) NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tags_user_name (user_id, name),
    CONSTRAINT fk_tags_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. menu_tags
CREATE TABLE menu_tags (
    menu_id BIGINT NOT NULL,
    tag_id  BIGINT NOT NULL,
    PRIMARY KEY (menu_id, tag_id),
    INDEX idx_menu_tags_tag_id (tag_id),
    CONSTRAINT fk_menu_tags_menu FOREIGN KEY (menu_id) REFERENCES menus (id),
    CONSTRAINT fk_menu_tags_tag FOREIGN KEY (tag_id) REFERENCES tags (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. restaurants
CREATE TABLE restaurants (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         BIGINT        NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    address         VARCHAR(300)  NULL,
    phone           VARCHAR(20)   NULL,
    latitude        DECIMAL(10,7) NOT NULL,
    longitude       DECIMAL(10,7) NOT NULL,
    naver_url       VARCHAR(500)  NULL,
    naver_place_id  VARCHAR(100)  NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME      NULL,
    PRIMARY KEY (id),
    INDEX idx_restaurants_user_deleted (user_id, deleted_at),
    INDEX idx_restaurants_location (user_id, latitude, longitude),
    INDEX idx_restaurants_naver_place (naver_place_id),
    CONSTRAINT fk_restaurants_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. menu_restaurants
CREATE TABLE menu_restaurants (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    menu_id       BIGINT   NOT NULL,
    restaurant_id BIGINT   NOT NULL,
    rating        TINYINT  NULL,
    memo          TEXT     NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_menu_restaurant (menu_id, restaurant_id),
    INDEX idx_mr_restaurant_id (restaurant_id),
    CONSTRAINT fk_menu_restaurants_menu FOREIGN KEY (menu_id) REFERENCES menus (id),
    CONSTRAINT fk_menu_restaurants_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. histories
CREATE TABLE histories (
    id              BIGINT     NOT NULL AUTO_INCREMENT,
    user_id         BIGINT     NOT NULL,
    menu_id         BIGINT     NULL,
    restaurant_id   BIGINT     NULL,
    is_visited      TINYINT(1) NOT NULL DEFAULT 0,
    recommended_at  DATETIME   NOT NULL,
    visited_at      DATETIME   NULL,
    PRIMARY KEY (id),
    INDEX idx_histories_user_time (user_id, recommended_at DESC),
    INDEX idx_histories_visited (user_id, is_visited, recommended_at DESC),
    CONSTRAINT fk_histories_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_histories_menu FOREIGN KEY (menu_id) REFERENCES menus (id) ON DELETE SET NULL,
    CONSTRAINT fk_histories_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. history_filter_conditions
CREATE TABLE history_filter_conditions (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    history_id   BIGINT       NOT NULL,
    filter_type  VARCHAR(20)  NOT NULL,
    filter_value VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_hfc_history_id (history_id),
    INDEX idx_hfc_type_value (history_id, filter_type),
    CONSTRAINT fk_hfc_history FOREIGN KEY (history_id) REFERENCES histories (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
