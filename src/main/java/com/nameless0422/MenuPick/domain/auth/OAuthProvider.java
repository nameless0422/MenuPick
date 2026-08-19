package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.OAuthUserProfile;

public interface OAuthProvider {

    String getProviderName();

    OAuthUserProfile getUserProfile(String code);

    /**
     * 사용자를 보낼 제공자 동의 화면의 전체 URL을 만든다.
     *
     * <p>이 조립을 서버가 하는 이유는 client_id를 브라우저에 내려보내지 않기 위해서다.
     * 프론트가 만들면 client_id가 번들에 인라인되어 공개되는데, 카카오는 이 값(REST API 키)이
     * 로컬 API 자격증명이기도 해서 누구나 꺼내 검색 할당량을 소진시킬 수 있다.
     * redirect_uri도 서버 설정 하나만 보게 되어, 프론트·백엔드 값이 어긋나 토큰 교환이
     * 실패하던 종류의 사고가 구조적으로 사라진다.
     *
     * <p>state는 호출자(브라우저)가 만들어 넘긴다. 콜백에서 sessionStorage 저장값과 대조하는
     * 주체가 브라우저이므로, 서버가 새로 만들면 대조할 짝이 없어진다. 서버는 형식만 검증하고
     * 그대로 실어 보낸다.
     */
    String buildAuthorizeUrl(String state);
}
