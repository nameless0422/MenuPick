package com.nameless0422.MenuPick.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 프론트엔드 오리진 허용 목록. 값이 비어 있으면(기본) CORS가 전부 막힌다 — fail-safe 기본값.
 * local은 Vite 기본 포트(5173), dev/prod는 FRONTEND_ORIGIN env로 채운다.
 */
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(
        List<String> allowedOrigins
) {}
