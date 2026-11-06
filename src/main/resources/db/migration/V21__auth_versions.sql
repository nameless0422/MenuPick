-- 인증 무효화 버전 (보안 수정).
--
-- users.auth_version: 이 값과 토큰의 ver 클레임이 다르면 Access/Refresh Token을 거부한다.
--   로그아웃·탈퇴·비밀번호 변경·소셜 연동 변경·Refresh 재사용 탐지 때 올린다.
-- auth_providers.credential_version: 비밀번호가 바뀔 때마다 올린다. 메일 링크(인증·재설정)가
--   발급 시점의 값에 묶여, 그 뒤 비밀번호가 바뀌면 Redis 폐기 여부와 무관하게 DB에서 거절된다.
--
-- 기본값 0: ver 클레임이 없는 배포 전 토큰을 0으로 해석하므로 배포만으로 로그인이 끊기지 않는다.
ALTER TABLE users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE auth_providers ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;
