package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * OAuth 제공자(Kakao/Google) 공통 HTTP 처리.
 *
 * <p>토큰 교환·프로필 조회의 오류 매핑을 한 곳에 모은다.
 * <ul>
 *   <li>업스트림 4xx → {@link ErrorCode#OAUTH_INVALID_CODE}(401). 만료·재사용된 인가 코드,
 *       잘못된 redirect_uri 등은 클라이언트가 재로그인으로 해결할 문제다.</li>
 *   <li>업스트림 5xx·네트워크 장애 → {@link ErrorCode#OAUTH_PROVIDER_ERROR}(502).
 *       외부 제공자 장애임을 상태코드로 구분하고 상세는 로그로 남긴다.</li>
 * </ul>
 */
@Slf4j
final class OAuthHttpSupport {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private OAuthHttpSupport() {
    }

    /**
     * 인가 코드를 액세스 토큰으로 교환한다.
     *
     * <p>바디는 {@link BodyInserters#fromFormData}로 만든다 — 문자열 연결은 code·redirect_uri에
     * 들어온 {@code &}/{@code =}가 그대로 파라미터 구분자로 해석돼 파라미터 인젝션이 가능하다.
     */
    static String exchangeAccessToken(WebClient webClient,
                                      OAuthProperties.ProviderConfig config,
                                      String providerName,
                                      String code) {
        Map<String, Object> tokenResponse = call(providerName, "토큰 교환", () -> webClient.post()
                .uri(config.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                        .with("client_id", nullToEmpty(config.clientId()))
                        .with("client_secret", nullToEmpty(config.clientSecret()))
                        .with("redirect_uri", nullToEmpty(config.redirectUri()))
                        .with("code", nullToEmpty(code)))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> invalidCode(providerName, "토큰 교환", response))
                .onStatus(HttpStatusCode::is5xxServerError, response -> upstreamError(providerName, "토큰 교환", response))
                .bodyToMono(JSON_MAP)
                .block());

        // 카카오는 일부 오류를 200 + {"error":...} 형태로 돌려준다 — access_token 부재를 직접 확인한다.
        Object accessToken = tokenResponse == null ? null : tokenResponse.get("access_token");
        if (!(accessToken instanceof String token) || token.isBlank()) {
            log.warn("{} 토큰 교환 응답에 access_token이 없습니다: keys={}",
                    providerName, tokenResponse == null ? null : tokenResponse.keySet());
            throw new BusinessException(ErrorCode.OAUTH_INVALID_CODE);
        }
        return token;
    }

    /** 액세스 토큰으로 사용자 프로필을 조회한다. */
    static Map<String, Object> fetchUserInfo(WebClient webClient,
                                             OAuthProperties.ProviderConfig config,
                                             String providerName,
                                             String accessToken) {
        Map<String, Object> userInfo = call(providerName, "프로필 조회", () -> webClient.get()
                .uri(config.userInfoUri())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> invalidCode(providerName, "프로필 조회", response))
                .onStatus(HttpStatusCode::is5xxServerError, response -> upstreamError(providerName, "프로필 조회", response))
                .bodyToMono(JSON_MAP)
                .block());

        if (userInfo == null) {
            log.warn("{} 프로필 조회 응답이 비어 있습니다", providerName);
            throw new BusinessException(ErrorCode.OAUTH_INVALID_CODE);
        }
        return userInfo;
    }

    /** 중첩 오브젝트를 NPE 없이 꺼낸다 — 동의 항목 거부 시 필드 자체가 없을 수 있다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        if (source == null) {
            return Map.of();
        }
        Object value = source.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /** 값이 문자열이 아니면(부재·타입 불일치) null. */
    static String stringOrNull(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof String s ? s : null;
    }

    /**
     * 소셜 식별자를 문자열로 뽑는다. 없으면 계정 식별이 불가능하므로 실패로 처리한다
     * (String.valueOf로 그냥 넘기면 서로 다른 사용자가 "null" socialId를 공유하게 된다).
     */
    static String requireSocialId(Map<String, Object> userInfo, String providerName) {
        Object id = userInfo == null ? null : userInfo.get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            log.warn("{} 프로필 응답에 id가 없습니다", providerName);
            throw new BusinessException(ErrorCode.OAUTH_INVALID_CODE);
        }
        return String.valueOf(id);
    }

    private static <T> T call(String providerName, String step, java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // 연결 실패·타임아웃·응답 디코딩 실패 등
            log.error("{} {} 호출 실패: {}", providerName, step, e.getMessage(), e);
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
        }
    }

    private static Mono<Throwable> invalidCode(String providerName, String step, ClientResponse response) {
        return response.bodyToMono(String.class).defaultIfEmpty("")
                .map(body -> {
                    log.warn("{} {} 4xx 응답: status={}, body={}", providerName, step, response.statusCode(), body);
                    return new BusinessException(ErrorCode.OAUTH_INVALID_CODE);
                });
    }

    private static Mono<Throwable> upstreamError(String providerName, String step, ClientResponse response) {
        return response.bodyToMono(String.class).defaultIfEmpty("")
                .map(body -> {
                    log.error("{} {} 5xx 응답: status={}, body={}", providerName, step, response.statusCode(), body);
                    return new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
                });
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
