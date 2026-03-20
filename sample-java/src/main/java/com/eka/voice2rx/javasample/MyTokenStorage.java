package com.eka.voice2rx.javasample;

import com.eka.networking.token.TokenStorage;

import org.jetbrains.annotations.NotNull;

public class MyTokenStorage implements TokenStorage {

    private static final String REFRESH_TOKEN = "b763bb1d2b7b47d1a0992e84389061b2";
    private String accessToken =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJkb2Mtd2ViIiwiYi1pZCI6IjcxNzU2MjAyNDU0ODM3NzciLCJjYyI6eyJlc2MiOjEsInBleCI6MTgwMTUyNjQwMCwicHNuIjoiRCIsInBzdCI6InRydWUiLCJzdHkiOiJwIn0sImRvYiI6IjIwMjUtMDgtMjUiLCJleHAiOjE3NzM5OTMyMjgsImZuIjoiSW10aXlheiIsImdlbiI6Ik0iLCJpYXQiOjE3NzM5OTE0MjgsImlkcCI6Im1vYiIsImlzcyI6ImVtci5la2EuY2FyZSIsImp0aSI6ImQyNWU0ZmMwLTI5YWEtNDcyZS1hMjA3LTVjMDk2MWNiODk3NyIsImxuIjoibSwiLCJvaWQiOiIxNzU2MjAyNDU1MDI4NDIiLCJwcmkiOnRydWUsInBzIjoiRCIsInIiOiJJTiIsInMiOiJEciIsInV1aWQiOiI2NDIyYmRjMi0yMTgyLTRhMTMtYjYyMC0wNDdmNTBjZDQyZTgiLCJ3LWlkIjoiNzE3NTYyMDI0NTQ4Mzc3NyIsInctbiI6IkltdGl5YXoifQ.vgztROX25KzXDqyKPhS6PIPlERePIHxnUm98o4Mj3iQ";

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
