package com.nameless0422.MenuPick.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHardeningConfigurationTest {

    @Test
    @DisplayName("이미지와 OCI nginx가 숨김파일 경로를 404로 차단한다")
    void nginxConfigurationsRejectHiddenFiles() throws IOException {
        assertThat(read("frontend/nginx.conf")).contains("location ~ /\\. { return 404; }");
        assertThat(read("deploy/oci/nginx-tls.conf")).contains("location ~ /\\. { return 404; }");
    }

    @Test
    @DisplayName("두 nginx 설정의 접근 로그에 인증 링크의 쿼리와 Referer가 남지 않는다")
    void nginxAccessLogsOmitQueryAndReferer() throws IOException {
        for (String path : new String[]{"frontend/nginx.conf", "deploy/oci/nginx-tls.conf"}) {
            String config = read(path);
            String format = config.substring(
                    config.indexOf("log_format menupick_safe"),
                    config.indexOf("server {"));

            assertThat(config).contains("map $request_uri $menupick_request_path");
            assertThat(format)
                    .contains("$menupick_request_path")
                    .doesNotContain("$request_uri", "$args", "$http_referer")
                    .doesNotContainPattern("\\$request\\b");
        }

        assertThat(read("frontend/nginx.conf"))
                .contains("access_log /var/log/nginx/access.log menupick_safe;");
        // TLS 설정은 server 블록이 둘이고, access_log는 블록마다 따로 적용해야 한다.
        assertThat(read("deploy/oci/nginx-tls.conf"))
                .containsPattern("(?s)access_log /var/log/nginx/access\\.log menupick_safe;"
                        + ".*access_log /var/log/nginx/access\\.log menupick_safe;");
    }

    @Test
    @DisplayName("TLS nginx가 문서 응답에 최소 CSP를 강제하고 전체 정책은 보고 전용으로 유지한다")
    void tlsNginxEnforcesBaselineCsp() throws IOException {
        String config = read("deploy/oci/nginx-tls.conf");
        String enforced = "add_header Content-Security-Policy "
                + "\"object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'\" always;";

        // add_header는 상속이 아니라 덮어쓰기라 server 블록과 /index.html 양쪽에 필요하다.
        assertThat(config.split(java.util.regex.Pattern.quote(enforced), -1)).hasSize(3);
        assertThat(config.split("add_header Content-Security-Policy-Report-Only", -1)).hasSize(3);
        assertThat(config.split("add_header Referrer-Policy \"strict-origin\" always;", -1)).hasSize(4);
        assertThat(config).doesNotContain("strict-origin-when-cross-origin\" always");
    }

    @Test
    @DisplayName("운영 SSH는 opc 공개키 로그인만 허용한다")
    void sshAllowsOnlyOpcPublicKeyLogin() throws IOException {
        String config = read("deploy/oci/sshd-hardening.conf");

        assertThat(config).contains(
                "PasswordAuthentication no",
                "KbdInteractiveAuthentication no",
                "PubkeyAuthentication yes",
                "PermitRootLogin no",
                "MaxAuthTries 3",
                "AllowUsers opc");
    }

    @Test
    @DisplayName("fail2ban이 반복 SSH 실패를 자동 차단한다")
    void fail2banProtectsSsh() throws IOException {
        String config = read("deploy/oci/fail2ban-sshd.local");

        assertThat(config).contains(
                "[sshd]",
                "enabled = true",
                "maxretry = 3",
                "bantime.increment = true");
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(repoRoot().resolve(relativePath), StandardCharsets.UTF_8);
    }

    private Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("docker-compose.prod.yml"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("docker-compose.prod.yml이 있는 리포 루트").isNotNull();
        return dir;
    }
}
