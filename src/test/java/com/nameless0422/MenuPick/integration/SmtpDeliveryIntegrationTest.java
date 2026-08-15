package com.nameless0422.MenuPick.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가입 메일이 <b>실제 SMTP 소켓으로 나가는지</b> 확인한다.
 *
 * <p>이 경로는 지금까지 한 번도 실행된 적이 없다. {@code EmailSenderTest}·{@code AuthMailerTest}는
 * {@code JavaMailSender}를 목으로 바꿔 "호출했는가"만 보고, 로컬 프로파일은 SMTP를 아예 지정하지
 * 않아 링크를 로그로 떨어뜨린다. 그 경로는 {@code JavaMailSender} 빈을 만들지도 않으므로
 * <b>메일 조립·전송이라는 동작 자체가 한 번도 일어나지 않는다</b>. 제목이 깨지거나 링크가
 * 잘못 조립돼도 로그에서는 멀쩡해 보인다.
 *
 * <p>가입의 모든 경로가 이 메일을 통과해야 하므로, 발송이 안 되면 <b>아무도 계정을 만들 수 없다</b>.
 * 그런데 발송 실패는 {@code AuthMailer}가 삼켜서 가입 응답은 그대로 201이다 — 즉 운영에서
 * 이 결함은 "가입은 되는데 아무도 로그인을 못 하는" 모습으로만 드러난다.
 *
 * <p><b>덮지 못하는 것</b>: 운영 설정은 {@code starttls.enable=true} + {@code auth=true}인데
 * 여기서는 평문 Mailpit을 상대한다. TLS 핸드셰이크와 SMTP 인증은 실제 제공자를 붙일 때
 * 따로 확인해야 한다. 여기서 닫는 구멍은 "SMTP 대화가 성립하고 메일이 제대로 조립돼 나가는가"다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 인증 경로는 IP 기준 10회/분으로 막힌다. 이 테스트는 레이트 리밋이 아니라 메일을
                // 보는 것이라, 한도에 걸려 429가 나면 원인을 메일에서 찾게 된다. 넉넉히 올려 둔다.
                "rate-limit.auth-limit-per-minute=100"
        })
@ActiveProfiles("integration")
class SmtpDeliveryIntegrationTest extends AbstractIntegrationTest {

    /**
     * 받은 메일을 밖으로 내보내지 않고 붙잡아 두는 메일 서버. 개발용 compose와 같은 이미지다.
     *
     * <p>MySQL/Redis 컨테이너와 같은 이유로 JVM당 1회만 띄운다 — {@code @Testcontainers}로
     * 클래스 종료 시 내리면, 스프링 테스트 컨텍스트 캐시가 살아 있는 다음 클래스가 죽은 포트를 본다.
     */
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:latest")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025));

    static {
        MAILPIT.start();
    }

    /**
     * SMTP 좌표를 주입한다.
     *
     * <p>{@code app.mail.base-url}까지 고정하는 이유: 기본값에 기대면 나중에 그 기본값이 바뀔 때
     * 링크 추출이 조용히 깨진다. 여기서 못 박아 두면 테스트가 무엇을 가정하는지 파일 안에서 보인다.
     */
    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("app.mail.base-url", () -> BASE_URL);
    }

    private static final String BASE_URL = "https://menupick.test";

    /** 메일 링크에서 토큰만 뽑아낸다. AuthMailer가 {@code ?token=...} 형태로 조립한다. */
    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

    /**
     * 메일 도착을 기다리는 상한.
     *
     * <p>{@code AuthMailer}의 발송은 {@code @Async(mailTaskExecutor)}라 가입 응답이 돌아온
     * 시점에는 아직 나가지 않았을 수 있다. 즉시 단언하면 거의 항상 실패한다.
     */
    private static final Duration MAIL_TIMEOUT = Duration.ofSeconds(20);

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    // ---- 테스트 ----

    @Test
    @DisplayName("가입하면 인증 메일이 실제 SMTP로 나가고, 그 링크의 토큰으로 인증·로그인까지 이어진다")
    void signupMailTravelsOverSmtpAndCompletesVerification() throws Exception {
        String email = uniqueEmail();
        String nickname = "메일테스터-" + UUID.randomUUID().toString().substring(0, 8);

        HttpResponse<String> signup = post("/api/v1/auth/signup", """
                {"email":"%s","password":"correct-horse-battery","nickname":"%s"}
                """.formatted(email, nickname));
        assertThat(signup.statusCode()).isEqualTo(201);

        JsonNode mail = awaitMailTo(email);

        // 제목이 깨지면 실제 메일함에서 알아볼 수 없는 메일이 된다. 한글이 그대로 살아 돌아오는지 본다.
        assertThat(mail.path("Subject").asText())
                .as("제목이 깨졌다 — 메일 인코딩이 UTF-8이 아니다")
                .isEqualTo("[메뉴픽] 이메일 인증을 완료해주세요");
        assertThat(recipientOf(mail)).isEqualTo(email);
        assertThat(mail.path("From").path("Address").asText()).isEqualTo("no-reply@menupick.local");

        String body = mail.path("Text").asText();
        assertThat(body)
                .as("본문의 한글도 깨지면 안 된다")
                .contains("메뉴픽 가입을 환영합니다", "24시간 동안 유효합니다");
        assertThat(body)
                .as("사용자가 실제로 눌러야 하는 링크다 — 여기가 틀리면 아무도 인증을 못 한다")
                .contains(BASE_URL + "/verify-email?token=");

        // 여기부터가 이 테스트의 핵심: 메일에서 뽑은 토큰이 실제로 통하는가.
        String token = extractToken(body);
        HttpResponse<String> verify = post("/api/v1/auth/verify-email", """
                {"token":"%s"}
                """.formatted(token));

        assertThat(verify.statusCode()).isEqualTo(200);
        String accessToken = json.readTree(verify.body()).path("data").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        // 인증과 동시에 로그인된다. 발급받은 토큰이 실제로 쓸 수 있는 것인지까지 확인한다.
        HttpResponse<String> me = get("/api/v1/auth/me", accessToken);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).path("data").path("email").asText()).isEqualTo(email);
    }

    @Test
    @DisplayName("비밀번호 재설정 메일도 같은 경로로 나간다 — 링크 경로만 다르다")
    void passwordResetMailAlsoTravelsOverSmtp() throws Exception {
        String email = uniqueEmail();
        String nickname = "재설정테스터-" + UUID.randomUUID().toString().substring(0, 8);

        post("/api/v1/auth/signup", """
                {"email":"%s","password":"correct-horse-battery","nickname":"%s"}
                """.formatted(email, nickname));
        String verifyToken = extractToken(awaitMailTo(email).path("Text").asText());
        post("/api/v1/auth/verify-email", """
                {"token":"%s"}
                """.formatted(verifyToken));

        // 재설정은 인증을 마친 계정에만 나간다(LocalAuthService.requestPasswordReset의 filter).
        HttpResponse<String> reset = post("/api/v1/auth/password-reset", """
                {"email":"%s"}
                """.formatted(email));
        assertThat(reset.statusCode()).isEqualTo(200);

        JsonNode mail = awaitMailMatching(email, "[메뉴픽] 비밀번호 재설정 안내");

        String body = mail.path("Text").asText();
        // 인증 링크와 착지점이 다르다. 섞이면 사용자가 재설정 화면 대신 인증 화면으로 간다.
        assertThat(body).contains(BASE_URL + "/reset-password?token=");
        assertThat(body).doesNotContain("/verify-email");
        assertThat(body).contains("30분 동안 유효");
    }

    // ---- Mailpit ----

    private String mailpitApi() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
    }

    private JsonNode awaitMailTo(String recipient) throws Exception {
        return awaitMailMatching(recipient, null);
    }

    /**
     * 해당 수신자에게 온 메일이 도착할 때까지 기다렸다가 전문을 돌려준다.
     *
     * <p>Awaitility를 쓰지 않는 이유는 이 저장소에 없는 의존성이기 때문이다. 폴링 하나 때문에
     * 라이브러리를 들이기보다 여기서 직접 돈다.
     *
     * <p>메일함은 JVM당 하나뿐이라 다른 테스트의 메일이 섞여 있다. 수신자 주소로 걸러낸다 —
     * 그래서 각 테스트가 서로 다른 주소를 쓴다.
     */
    private JsonNode awaitMailMatching(String recipient, String subject) throws Exception {
        long deadline = System.nanoTime() + MAIL_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            JsonNode found = findMail(recipient, subject);
            if (found != null) {
                return found;
            }
            Thread.sleep(200);
        }

        throw new AssertionError(
                "메일이 " + MAIL_TIMEOUT.toSeconds() + "초 안에 오지 않았다: to=" + recipient
                        + (subject == null ? "" : ", subject=" + subject)
                        + ". 발송 실패는 AuthMailer가 삼키므로 가입 응답만으로는 드러나지 않는다 —"
                        + " 앱 로그의 '메일 발송 실패'를 확인할 것.");
    }

    private JsonNode findMail(String recipient, String subject) throws Exception {
        JsonNode list = json.readTree(
                httpGet(mailpitApi() + "/api/v1/messages?limit=200").body());

        for (JsonNode summary : list.path("messages")) {
            if (!recipient.equals(recipientOf(summary))) {
                continue;
            }
            if (subject != null && !subject.equals(summary.path("Subject").asText())) {
                continue;
            }
            // 목록 응답에는 본문이 없다(Snippet만 있다). 전문을 따로 받아야 링크가 온전하다.
            return json.readTree(
                    httpGet(mailpitApi() + "/api/v1/message/" + summary.path("ID").asText()).body());
        }
        return null;
    }

    /** Mailpit의 To는 {@code [{Name, Address}]} 배열이다. 이 앱은 수신자를 하나만 넣는다. */
    private String recipientOf(JsonNode message) {
        return message.path("To").path(0).path("Address").asText();
    }

    private String extractToken(String body) {
        Matcher matcher = TOKEN_IN_LINK.matcher(body);
        assertThat(matcher.find())
                .as("본문에서 토큰을 찾지 못했다. 링크 조립(AuthMailer.link)이 바뀌었는지 확인할 것:\n%s", body)
                .isTrue();
        return matcher.group(1);
    }

    // ---- HTTP ----

    private String appUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(appUrl(path)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String accessToken) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(appUrl(path)))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpGet(String url) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 이메일·닉네임 모두 UNIQUE라 회차마다 새 값이 필요하다. */
    private String uniqueEmail() {
        return "smtp-" + UUID.randomUUID().toString().substring(0, 12) + "@menupick.test";
    }
}
