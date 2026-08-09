package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.OAuthUserProfile;

public interface OAuthProvider {

    String getProviderName();

    OAuthUserProfile getUserProfile(String code);
}
