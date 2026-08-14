package com.nameless0422.MenuPick.common.logging;

import java.util.UUID;

/**
 * 로그 상관관계 식별자 생성.
 *
 * <p>요청 경로({@link TraceIdFilter})와 스케줄러가 같은 형식을 쓰도록 한 곳에 둔다.
 * 형식이 갈리면 로그를 한 눈금으로 훑을 수 없다.
 */
public final class TraceIds {

    private TraceIds() {
    }

    /** UUID 앞 16자리(64비트)만 쓴다 — 로그 줄이 길어지지 않으면서 상관관계 용도에는 충분하다. */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
