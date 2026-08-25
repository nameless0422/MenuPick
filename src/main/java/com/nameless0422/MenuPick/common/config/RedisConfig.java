package com.nameless0422.MenuPick.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Configuration
@EnableCaching
public class RedisConfig implements CachingConfigurer {

    /**
     * 캐시 값 직렬화기.
     *
     * <p>Jackson2 판({@code GenericJackson2JsonRedisSerializer})은 Boot 4.1에서 제거 예정으로
     * 표시됐다 — 빌드는 통과하지만 다음 major에서 컴파일이 깨진다. 후속 클래스는 no-arg 생성자가
     * 없고 빌더로 만든다.
     *
     * <p><b>기본 타이핑이 필요한 이유.</b> 캐시된 값은 {@code Object}로 되살아나므로 JSON에 타입
     * 정보가 없으면 record DTO가 {@code LinkedHashMap}으로 복원되고, Spring Cache가 메서드
     * 반환 타입으로 캐스팅하는 순간 {@code ClassCastException}이 난다.
     *
     * <p><b>그런데 무제한으로 켜지 않는다.</b> 옛 no-arg 생성자는 {@code @class}로 전면 다형성
     * 타이핑을 켰다 — Redis에 쓰기가 가능한 누군가가 임의 클래스 이름을 심으면 역직렬화가 그 클래스를
     * 인스턴스화한다(가젯 체인). 실제로 쓰는 값은 우리 DTO와 그 안의 컬렉션·문자열뿐이므로 거기까지만
     * 허용한다. 허용 목록을 벗어난 값은 역직렬화가 거부되고, 아래 {@link CacheErrorHandler}가 그
     * 실패를 캐시 미스로 강등해 원본 호출로 흘러간다.
     *
     * <p>직렬화 형식이 Jackson2 판과 달라 이미 Redis에 들어 있는 값은 새 직렬화기로 읽히지 않을 수
     * 있다. 이 캐시는 전부 외부 API 응답이라 최악이라야 TTL(최대 24시간) 동안 적중률이 떨어질 뿐이다.
     */
    private static GenericJacksonJsonRedisSerializer cacheValueSerializer() {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.nameless0422.MenuPick.")
                .allowIfSubType("java.util.")
                .allowIfSubType(String.class)
                .build();

        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(typeValidator)
                .build();
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(cacheValueSerializer()))
                .disableCachingNullValues()
                .entryTtl(Duration.ofHours(1));

        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
                "naverGeocode", defaultConfig.entryTtl(Duration.ofHours(24)),
                "naverReverseGeocode", defaultConfig.entryTtl(Duration.ofHours(24)),
                "kakaoKeywordSearch", defaultConfig.entryTtl(Duration.ofHours(1)),
                "kakaoCategorySearch", defaultConfig.entryTtl(Duration.ofHours(1))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }

    /**
     * 캐시 연산 실패를 삼키고 캐시 미스로 강등한다.
     *
     * <p>기본 {@code SimpleCacheErrorHandler}는 Redis 예외를 그대로 던지므로,
     * Redis가 잠깐 죽으면 캐시가 붙은 API가 전부 500으로 무너진다.
     * 여기 붙은 캐시는 전부 "외부 API 호출을 아껴주는" 성능 최적화이지 정합성 요소가 아니므로,
     * RateLimitFilter의 fail-open 철학과 동일하게 실패를 warn 로깅 후 무시한다.
     * (get 실패 → 원본 호출, put/evict 실패 → 다음 TTL 만료 시 자연 복구)
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }

    static class LoggingCacheErrorHandler implements CacheErrorHandler {

        @Override
        public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
            log.warn("캐시 조회 실패 — 캐시 미스로 처리합니다: cache={}, keyHash={}, cause={}",
                    cache.getName(), keyDigest(key), exception.getMessage());
        }

        @Override
        public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
            log.warn("캐시 저장 실패 — 무시합니다: cache={}, keyHash={}, cause={}",
                    cache.getName(), keyDigest(key), exception.getMessage());
        }

        @Override
        public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
            log.warn("캐시 삭제 실패 — 무시합니다: cache={}, keyHash={}, cause={}",
                    cache.getName(), keyDigest(key), exception.getMessage());
        }

        @Override
        public void handleCacheClearError(RuntimeException exception, Cache cache) {
            log.warn("캐시 전체 삭제 실패 — 무시합니다: cache={}, cause={}",
                    cache.getName(), exception.getMessage());
        }

        /**
         * 캐시 키를 로그에 그대로 남기지 않기 위한 짧은 지문.
         *
         * <p>카카오 프록시의 캐시 키는 {@code 진주회관:FD6:126.985:37.561:500:1:15:accuracy}처럼
         * <b>사용자가 입력한 상호명과 실제 좌표</b>(100m 격자로 반올림)를 담는다.
         * {@code AccessLogFilter}가 쿼리 값을 일부러 빼고 파라미터 이름만 남기는 것과 같은
         * 이유다 — 그대로 찍으면 누가 무엇을 어디서 찾았는지가 로그에 쌓인다
         * (docs/PrivacyReview.md의 "위치정보는 별도로 저장하지 않습니다"에도 어긋난다).
         * Redis가 30초만 흔들려도 그 사이 들어온 모든 검색이 한 줄씩 남는다.
         *
         * <p>지문을 남기는 목적은 <b>같은 키가 반복해서 실패하는지 묶어 보는 것</b>뿐이다.
         * 비밀로 지키는 값이 아니다 — 짧은 검색어는 사전 대입으로 되맞출 수 있다.
         * 원문이 필요하면 키를 만드는 쪽에서 DEBUG로 찍는다.
         */
        static String keyDigest(Object key) {
            if (key == null) {
                return "null";
            }
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(key.toString().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest, 0, 4);
            } catch (NoSuchAlgorithmException e) {
                // SHA-256은 모든 JVM이 제공하도록 표준이 요구하지만, 로깅 때문에 예외가
                // 새어 나가면 이 핸들러가 지키려던 fail-open 자체가 깨진다. 여기서 닫는다.
                return "unavailable";
            }
        }
    }
}
