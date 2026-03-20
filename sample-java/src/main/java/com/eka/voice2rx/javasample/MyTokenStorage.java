package com.eka.voice2rx.javasample;

import com.eka.networking.token.TokenStorage;

import org.jetbrains.annotations.NotNull;

public class MyTokenStorage implements TokenStorage {

    private static final String REFRESH_TOKEN = "";
    private String accessToken = "";

    @NotNull
    @Override
    public String getAccessToken() {
        return accessToken;
    }

    @NotNull
    @Override
    public String getRefreshToken() {
        return REFRESH_TOKEN;
    }

    @Override
    public void saveTokens(@NotNull String accessToken, @NotNull String refreshToken) {
        this.accessToken = accessToken;
    }

    @Override
    public void onSessionExpired() {
        // Handle session expiry
    }
}
