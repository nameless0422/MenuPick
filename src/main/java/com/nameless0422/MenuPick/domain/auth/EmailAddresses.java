package com.nameless0422.MenuPick.domain.auth;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 이메일을 저장·조회·비교하는 형식으로 맞춘다.
 *
 * <p>{@code users.email}과 {@code auth_providers.social_id}는 바이트 단위로 비교하므로(V22) 대소문자
 * 무시는 이 정규화가 맡는다. 자체 계정 입력과 소셜 제공자가 준 주소가 모두 여기를 거쳐야 같은 주소가
 * 같은 값이 된다.
 */
final class EmailAddresses {

    private EmailAddresses() {}

    /** 공백 제거 → NFKC → 소문자 순. 전각 같은 호환 문자는 실제 주소로 수렴하고 accent는 남는다. */
    static String canonicalize(String email) {
        return Normalizer.normalize(email.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
