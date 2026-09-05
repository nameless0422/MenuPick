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
