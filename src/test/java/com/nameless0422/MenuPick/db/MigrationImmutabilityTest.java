package com.nameless0422.MenuPick.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 번 머지된 마이그레이션 파일은 다시 고치지 않는다 — 그 규칙을 사람 대신 지킨다.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>2026-08-28 운영 배포가 앱 기동 실패로 멈췄다. 원인은 커밋 {@code 86570a0}이 이미 운영에
 * 적용된 {@code V5__nickname_and_place_uniqueness.sql}에 <b>주석만</b> 덧붙인 것이었다.
 * Flyway 체크섬은 파일 전체의 CRC라 주석도 포함된다 — 실행되는 SQL이 한 글자도 안 바뀌었는데
 * {@code FlywayValidateException: Migration checksum mismatch for migration version 5}로
 * 컨테이너가 restart 루프에 빠졌고, 복구하려면 운영 DB에 직접 {@code flyway repair}를 걸어야 했다.
 *
 * <p>규칙 자체는 이미 문서에 있었다({@code docs/DecisionLog.md} D-031의 트레이드오프 항목:
 * "Flyway는 적용된 마이그레이션의 체크섬을 검증하므로 파일을 고치면 다음 기동이 통째로 실패한다").
 * 그런데도 사고가 났다. <b>주석은 괜찮겠지</b>라고 생각하는 순간이 정확히 함정이고, 로컬에서는
 * 아무 일도 일어나지 않는다 — 테스트도 CI도 매번 빈 DB에서 시작하므로 체크섬이 현재 파일 기준으로
 * 새로 계산되어 전부 초록불이다. <b>이미 그 마이그레이션을 적용한 DB에서만</b> 터진다.
 * 그래서 사람이 지킬 수 없고, 이 테스트가 대신 지킨다.
 *
 * <h2>새 마이그레이션을 추가할 때</h2>
 *
 * <p>이 테스트가 "manifest에 없다"고 실패하면서 붙여 넣을 줄을 그대로 출력한다. 그 줄을 아래
 * {@code EXPECTED}에 추가하면 된다 — 한 단계 늘어나는 대신, 나중에 그 파일을 건드리는 순간
 * 배포가 아니라 CI에서 걸린다.
 *
 * <h2>정말 고쳐야 한다면</h2>
 *
 * <p>내용을 바꿔야 하는 상황이라면 그건 <b>새 버전(V10, V11…)으로 낸다.</b> 이미 적용된 파일을
 * 고치는 선택지는 없다. 정말 불가피하다면(예: 아직 어떤 환경에도 적용되지 않은 직전 커밋의 실수)
 * 해시를 갱신하되, 그 마이그레이션이 적용된 모든 환경에서 {@code flyway repair}가 필요하다는 것을
 * PR에 적어야 한다.
 */
class MigrationImmutabilityTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /**
     * 파일명 → 정규화된 내용의 SHA-256.
     *
     * <p>줄바꿈을 LF로 맞춘 뒤 해시한다. 이 리포는 Windows에서 개발하고 Linux에서 CI를 도는데
     * {@code core.autocrlf} 때문에 작업 트리의 줄바꿈이 갈리기 때문이다. 정규화하지 않으면
     * 내용이 같아도 두 환경의 해시가 달라 테스트가 늘 빨간불이 된다.
     * (Flyway의 CRC32도 같은 이유로 줄 종결자를 무시한다.)
     */
    private static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry("V1__init_schema.sql",
                    "b3e133b29ad138ff5e1178ce421cf7b984ab8242722245d8db62cf41ce3629bc"),
            Map.entry("V2__align_rating_column_type.sql",
                    "fbda90bded955d152c6823ff1d33c08fc0b8588b7beaadd9cc2b7d3ac753f13b"),
            Map.entry("V3__performance_indexes.sql",
                    "a98484d33034fc24dd8d72542a8d6e68e7e78b819d251e54da22f3cf4a84e0df"),
            Map.entry("V4__local_account.sql",
                    "7e4af467a8656ae6b88830af7201f2151045338d4a9ad5ec300e39fd0f26540b"),
            Map.entry("V5__nickname_and_place_uniqueness.sql",
                    "9925e35fde5697c27ba533b1feba556b1c77e6bfc9ca8f7710f54096e031b388"),
            Map.entry("V6__drop_unused_indexes.sql",
                    "dedd2d3233310a28c0e4feca7ba69d35e4e726f4656dfc07ad1adfbc382f53b0"),
            Map.entry("V7__emoji_safe_collation.sql",
                    "2044ba2ed6e90ded49221db9184fd8526a144a3195bdf7029ab66a7ff0c3abcd"),
            Map.entry("V8__drop_redundant_history_index.sql",
                    "5e68e854cfdc78698c54589cc38b1cabe5c09a540363e9b8e96ccb4895854fd7"),
            Map.entry("V9__optimistic_locking.sql",
                    "53be1d00b338ce4f93b2e818088654f9af677cefb71a2bfec29f814ab77b2490"));

    @Test
    @DisplayName("이미 머지된 마이그레이션 파일은 내용이 바뀌지 않았다")
    void appliedMigrationsAreUnchanged() {
        Map<String, String> actual = hashAll();

        // 파일이 하나도 안 읽혔는데 통과하면, 규칙이 지켜져서가 아니라 아무것도 안 봐서 초록불이다.
        assertThat(actual).as("마이그레이션 디렉터리를 읽지 못했다: %s", MIGRATIONS.toAbsolutePath())
                .hasSizeGreaterThanOrEqualTo(EXPECTED.size());

        Map<String, String> changed = new TreeMap<>();
        for (Map.Entry<String, String> pinned : EXPECTED.entrySet()) {
            String now = actual.get(pinned.getKey());
            if (now == null) {
                changed.put(pinned.getKey(), "삭제됨");
            } else if (!now.equals(pinned.getValue())) {
                changed.put(pinned.getKey(), "내용 변경됨 (현재 " + now + ")");
            }
        }

        assertThat(changed)
                .as("""
                        이미 머지된 마이그레이션이 바뀌었다. 주석 한 줄만 고쳐도 Flyway 체크섬이 달라져,
                        **이 마이그레이션을 이미 적용한 DB**에서만 다음 기동이 통째로 실패한다
                        (FlywayValidateException: Migration checksum mismatch). 로컬과 CI는 매번 빈 DB라
                        아무 일도 일어나지 않으므로 배포 순간에야 드러난다 — 2026-08-28에 실제로 그렇게 멈췄다.

                        고쳐야 할 내용이면 파일을 수정하지 말고 **새 버전(V%d 이상)으로 낼 것.**
                        """.formatted(EXPECTED.size() + 1))
                .isEmpty();
    }

    @Test
    @DisplayName("새로 추가된 마이그레이션은 manifest에 등록되어 있다")
    void newMigrationsAreRegistered() {
        Map<String, String> actual = hashAll();

        Map<String, String> unregistered = new LinkedHashMap<>();
        actual.forEach((name, hash) -> {
            if (!EXPECTED.containsKey(name)) {
                unregistered.put(name, hash);
            }
        });

        String paste = unregistered.entrySet().stream()
                .map(e -> "            Map.entry(\"%s\",%n                    \"%s\"),"
                        .formatted(e.getKey(), e.getValue()))
                .reduce("", (a, b) -> a + System.lineSeparator() + b);

        assertThat(unregistered.keySet())
                .as("""
                        새 마이그레이션이 %s의 EXPECTED에 없다. 아래를 그대로 붙여 넣을 것 \
                        (마지막 항목이면 뒤 쉼표를 지운다):
                        %s
                        """.formatted(MigrationImmutabilityTest.class.getSimpleName(), paste))
                .isEmpty();
    }

    private static Map<String, String> hashAll() {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            List<Path> sql = files.filter(p -> p.getFileName().toString().endsWith(".sql")).toList();
            Map<String, String> result = new TreeMap<>();
            for (Path path : sql) {
                result.put(path.getFileName().toString(), sha256(normalize(read(path))));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("마이그레이션 디렉터리를 읽지 못했다: " + MIGRATIONS, e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일을 읽지 못했다: " + path, e);
        }
    }

    /** CRLF·CR을 LF로 맞춘다 — 근거는 {@link #EXPECTED}의 주석. */
    private static String normalize(String content) {
        return content.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없는 JVM", e);
        }
    }
}
