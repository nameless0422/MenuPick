package com.nameless0422.MenuPick.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DatabasePoolConfigurationTest {

    @Test
    @DisplayName("운영 DB 풀은 환경변수로 조절하되 안전한 기본값 10을 유지한다")
    void productionPoolSizeIsConfigurableWithSafeDefault() throws IOException {
        Path root = repoRoot();
        String application = Files.readString(
                root.resolve("src/main/resources/application-prod.yml"), StandardCharsets.UTF_8);
        String compose = Files.readString(root.resolve("docker-compose.prod.yml"), StandardCharsets.UTF_8);

        assertThat(application).contains("maximum-pool-size: ${DB_MAX_POOL_SIZE:10}");
        assertThat(compose).contains("DB_MAX_POOL_SIZE: ${DB_MAX_POOL_SIZE:-10}");
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
