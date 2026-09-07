-- 사용자가 매번 다시 고르지 않아도 적용되는 알레르기·기피 태그.
CREATE TABLE user_default_excluded_tags (
    user_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, tag_id),
    CONSTRAINT fk_default_excluded_tags_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_default_excluded_tags_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
);
