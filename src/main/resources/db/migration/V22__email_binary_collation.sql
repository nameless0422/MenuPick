-- 이메일 식별자를 바이트 단위로 비교한다 (D-074).
--
-- V7의 utf8mb4_0900_ai_ci는 accent·호환 문자까지 접어 user@exámple.com과 user@example.com을
-- 같은 값으로 본다. 닉네임·태그에는 맞는 규칙이지만 로그인 식별자에는 맞지 않는다. 애플리케이션이
-- "다른 주소"로 다룬 값이 DB에서는 남의 행과 매칭돼 계정별 로그인 제한 우회, 주소 선점, 계정
-- 병합으로 이어진다. 그래서 이 두 컬럼만 바꾸고 나머지 문자열 컬럼은 V7 규칙을 유지한다.
--
-- 대소문자 무시는 DB가 아니라 애플리케이션 정규화(EmailAddresses)가 맡는다. 바이너리로 바꾼 뒤에는
-- 저장값도 그 형식이어야 하므로 먼저 소문자로 맞춘다. ai_ci UNIQUE 아래에서는 대소문자만 다른
-- 행이 공존할 수 없어 이 UPDATE가 중복을 만들지 않는다. 카카오·구글의 social_id는 제공자가 정한
-- 값이라 건드리지 않는다.
UPDATE users SET email = LOWER(email) WHERE email IS NOT NULL;
UPDATE auth_providers SET social_id = LOWER(social_id) WHERE provider = 'LOCAL';

-- ALGORITHM=INPLACE는 MySQL 8.4에서 ERROR 1846으로 거부됨을 확인했다(인덱스 컬럼의 콜레이션 변경).
-- 그래서 COPY를 명시한다(D-031). 두 테이블의 쓰기가 복사 동안 막히지만 사용자 수만큼의 행이라 짧다.
ALTER TABLE users
    MODIFY COLUMN email VARCHAR(255) COLLATE utf8mb4_0900_bin NULL,
    ALGORITHM=COPY, LOCK=SHARED;

ALTER TABLE auth_providers
    MODIFY COLUMN social_id VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    ALGORITHM=COPY, LOCK=SHARED;
