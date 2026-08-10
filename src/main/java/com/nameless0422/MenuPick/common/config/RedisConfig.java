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
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Configuration
@EnableCaching
public class RedisConfig implements CachingConfigurer {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
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
            log.warn("캐시 조회 실패 — 캐시 미스로 처리합니다: cache={}, key={}, cause={}",
                    cache.getName(), key, exception.getMessage());
        }

        @Override
        public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
            log.warn("캐시 저장 실패 — 무시합니다: cache={}, key={}, cause={}",
                    cache.getName(), key, exception.getMessage());
        }

        @Override
        public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
            log.warn("캐시 삭제 실패 — 무시합니다: cache={}, key={}, cause={}",
                    cache.getName(), key, exception.getMessage());
        }

        @Override
        public void handleCacheClearError(RuntimeException exception, Cache cache) {
            log.warn("캐시 전체 삭제 실패 — 무시합니다: cache={}, cause={}",
                    cache.getName(), exception.getMessage());
        }
    }
}
