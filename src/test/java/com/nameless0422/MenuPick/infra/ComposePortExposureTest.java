package com.nameless0422.MenuPick.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * compose 파일이 컨테이너 포트를 호스트의 어느 주소에 붙이는지 고정한다.
 *
 * <p>이 테스트가 있는 이유는 "포트 하나가 조용히 0.0.0.0으로 열리는" 사고가 실제로 났기
 * 때문이다(issue #87). app은 "프록시를 우회한 평문 HTTP 접근이 열린다"는 이유로 처음부터
 * {@code 127.0.0.1:8080:8080}으로 묶여 있었는데, 같은 스택의 web(nginx)만 {@code "80:80"} —
 * 즉 전 인터페이스 — 이었다. web은 {@code /api/}를 app에 그대로 넘기므로(frontend/nginx.conf)
 * 결과적으로 app의 루프백 바인딩이 무의미해졌고, {@code http://<서버IP>/api/v1/auth/login}에
 * 아이디·비밀번호가 평문으로 오갔다. refresh 쿠키가 {@code secure=true}라 로그인은 반쯤
 * 실패했지만, 그 시점엔 이미 요청 본문이 나간 뒤라 실패가 방어 수단이 되지 못한다.
 *
 * <p>사람이 리뷰에서 잡기 어려운 종류의 실수다 — {@code "80:80"}은 잘못돼 보이지 않는다.
 * 그래서 "이 리포의 compose가 publish하는 모든 포트는 바인드 주소를 명시하고, 그 주소는
 * 루프백이어야 한다"는 규칙 자체를 테스트로 박는다. 공개 진입점이 필요해지면
 * {@code WEB_HTTP_BIND}처럼 <b>기본값이 안전한 변수</b>로 열고, 그 결정을 이 테스트에
 * 명시적으로 기록해야 통과한다.
 *
 * <p>Docker는 필요 없다. 컨테이너를 띄우지 않고 YAML만 읽는다.
 */
class ComposePortExposureTest {

    /** {@code ${VAR:-기본값}} 형태의 compose 변수 치환. 여기서는 기본값이 곧 "리포가 약속한 값"이다. */
    private static final Pattern DEFAULTED_VAR =
            Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*:-([^}]*)}");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"docker-compose.prod.yml", "docker-compose.yml"})
    @DisplayName("compose가 publish하는 모든 포트는 루프백에만 붙는다")
    void everyPublishedPortBindsToLoopback(String composeFile) throws IOException {
        Map<String, List<String>> portsByService = publishedPorts(repoRoot().resolve(composeFile));

        // 한 서비스라도 포트를 안 읽었다면 파서가 조용히 빈 결과를 낸 것이다 — 그 상태로는
        // 이 테스트가 영원히 초록불이 되므로, 검사 대상이 존재한다는 것부터 확인한다.
        assertThat(portsByService).isNotEmpty();

        List<String> exposed = new ArrayList<>();
        portsByService.forEach((service, mappings) -> mappings.forEach(mapping -> {
            // "127.0.0.1:8080:8080" = 3조각(바인드 주소·호스트 포트·컨테이너 포트).
            // "8080:8080"처럼 2조각이면 바인드 주소가 없다는 뜻이고, 그 기본값이 0.0.0.0이다.
            String[] parts = resolveDefaults(mapping).split(":");
            if (parts.length < 3 || !"127.0.0.1".equals(parts[0])) {
                exposed.add(service + ": \"" + mapping + "\"");
            }
        }));

        if (!exposed.isEmpty()) {
            fail(composeFile + "이 루프백 밖으로 포트를 연다: " + exposed
                    + " — 바인드 주소를 생략하면 0.0.0.0이라 같은 망(또는 인터넷)의 누구나 붙는다."
                    + " 공개해야만 하는 포트라면 기본값이 127.0.0.1인 변수로 열고 이 테스트를 함께 고칠 것.");
        }
    }

    @Test
    @DisplayName("web의 80번은 변수로 열되 기본값은 루프백이다")
    void webHttpBindDefaultsToLoopback() throws IOException {
        // 서버에는 이 리포에 없는 오버레이(docker-compose.oci.yml)가 있고 TLS 종단은 그쪽이 맡는다.
        // compose는 여러 파일의 ports를 덮어쓰지 않고 이어 붙이므로, 오버레이가 같은 컨테이너
        // 포트를 다시 publish하면 포트 충돌로 기동이 실패한다. 그래서 오버레이가 아니라
        // .env.prod 한 줄로 조정할 수 있게 변수로 두되, 기본값은 안전한 쪽으로 고정한다.
        List<String> webPorts =
                publishedPorts(repoRoot().resolve("docker-compose.prod.yml")).get("web");

        assertThat(webPorts).containsExactly("${WEB_HTTP_BIND:-127.0.0.1}:80:80");
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> publishedPorts(Path composeFile) throws IOException {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(composeFile, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        }

        Map<String, Object> services = (Map<String, Object>) root.get("services");
        assertThat(services).as("services 블록").isNotNull();

        Map<String, List<String>> result = new LinkedHashMap<>();
        services.forEach((name, definition) -> {
            Object ports = ((Map<String, Object>) definition).get("ports");
            if (ports != null) {
                // 지금은 짧은 문법(문자열)만 쓴다. 긴 문법(맵)으로 바꾸면 여기서 깨지는데,
                // 그때는 이 테스트도 함께 고치는 게 맞다 — 노출 규칙은 문법과 무관하게 남아야 한다.
                result.put(name, ((List<Object>) ports).stream().map(String::valueOf).toList());
            }
        });
        return result;
    }

    private String resolveDefaults(String mapping) {
        Matcher matcher = DEFAULTED_VAR.matcher(mapping);
        return matcher.replaceAll(result -> Matcher.quoteReplacement(result.group(1)));
    }

    /**
     * 리포 루트. Gradle의 test 태스크는 프로젝트 디렉터리에서 돌지만, IDE는 다른 디렉터리에서
     * 돌리기도 한다. 어느 쪽이든 같은 파일을 보도록 위로 거슬러 올라가며 찾는다.
     */
    private Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("docker-compose.prod.yml"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("docker-compose.prod.yml이 있는 리포 루트").isNotNull();
        return dir;
    }
}
