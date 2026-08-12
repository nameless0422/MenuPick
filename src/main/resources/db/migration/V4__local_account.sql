-- 자체(이메일+비밀번호) 계정 지원.
--
-- 별도 테이블을 만들지 않고 auth_providers를 재사용한다. 이 테이블은 이미
-- "이 사용자가 인증할 수 있는 수단"의 목록이고, UNIQUE (provider, social_id)가
-- provider='LOCAL'일 때 곧바로 "이메일 중복 가입 불가" 제약이 되기 때문이다.

-- 1. LOCAL 행의 로그인 식별자는 이메일이다. social_id가 VARCHAR(100)이면
--    users.email(VARCHAR(255)) 길이의 주소를 담지 못해 가입이 실패하므로 폭을 맞춘다.
--    utf8mb4 기준 (20 + 255) * 4 = 1100바이트로, DYNAMIC 행 포맷의 인덱스 상한(3072)에 든다.
ALTER TABLE auth_providers
    MODIFY COLUMN social_id VARCHAR(255) NOT NULL;

-- 2. 소셜 행은 비밀번호가 없으므로 NULL 허용.
--    길이 100은 DelegatingPasswordEncoder가 붙이는 접두사("{bcrypt}") 8자 +
--    BCrypt 해시 60자를 담고, 이후 argon2 등으로 알고리즘을 옮길 여유까지 둔 값이다.
ALTER TABLE auth_providers
    ADD COLUMN password_hash VARCHAR(100) NULL AFTER social_id;

-- 3. users.email에는 "제공자가 소유를 검증한 주소"만 들어간다는 불변식이 있다.
--    AuthService가 이 값을 기준으로 소셜 연동을 기존 계정에 자동 병합하기 때문에,
--    미검증 주소가 섞이면 공격자가 남의 주소로 먼저 가입해 피해자의 소셜 로그인을
--    자기 계정으로 끌어오는 탈취가 가능해진다.
--    자체 계정도 메일 인증을 마쳐야만 users.email이 채워지며, 이 플래그가 그 사실을 명시한다.
--    기존 행은 전부 소셜 로그인으로 만들어졌고 검증된 이메일만 저장돼 있으므로 1로 채운다.
ALTER TABLE users
    ADD COLUMN email_verified TINYINT(1) NOT NULL DEFAULT 0 AFTER email;

UPDATE users SET email_verified = 1 WHERE email IS NOT NULL;
