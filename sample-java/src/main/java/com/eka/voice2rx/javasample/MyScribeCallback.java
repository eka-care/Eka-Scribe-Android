package com.eka.voice2rx.javasample;

import android.util.Log;

import com.eka.scribesdk.api.EkaScribeCallback;
import com.eka.scribesdk.api.models.ScribeError;
import com.eka.scribesdk.api.models.SessionEvent;
import com.eka.scribesdk.api.models.SessionResult;

import org.jetbrains.annotations.NotNull;

public class MyScribeCallback implements EkaScribeCallback {

    private static final String TAG = "MyScribeCallback";
    private final Listener listener;

    public MyScribeCallback(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onSessionStarted(@NotNull String sessionId) {
        Log.d(TAG, "Session started: " + sessionId);
        listener.onStarted(sessionId);
    }

    @Override
    public void onSessionPaused(@NotNull String sessionId) {
        Log.d(TAG, "Session paused: " + sessionId);
        listener.onPaused(sessionId);
    }

    @Override
    public void onSessionResumed(@NotNull String sessionId) {
        Log.d(TAG, "Session resumed: " + sessionId);
        listener.onResumed(sessionId);
    }

    @Override
    public void onSessionStopped(@NotNull String sessionId, int chunkCount) {
        Log.d(TAG, "Session stopped: " + sessionId + ", chunks=" + chunkCount);
        listener.onStopped(sessionId, chunkCount);
    }

    @Override
    public void onError(@NotNull ScribeError error) {
        Log.e(TAG, "Error: " + error.getCode() + " - " + error.getMessage());
        listener.onError(error);
    }

    @Override
    public void onSessionCompleted(@NotNull String sessionId, @NotNull SessionResult result) {
        Log.d(TAG, "Session completed: " + sessionId);
        listener.onCompleted(sessionId, result);
    }

    @Override
    public void onSessionFailed(@NotNull String sessionId, @NotNull ScribeError error) {
        Log.e(TAG, "Session failed: " + sessionId + " - " + error.getCode() + ": " + error.getMessage());
        listener.onFailed(sessionId, error);
    }

    @Override
    public void onSessionCancelled(@NotNull String sessionId) {
        Log.d(TAG, "Session cancelled: " + sessionId);
    }

    @Override
    public void onAudioFocusChanged(boolean hasFocus) {
        Log.d(TAG, "Audio focus changed: " + hasFocus);
    }

    @Override
    public void onSessionEvent(@NotNull SessionEvent event) {
        Log.d(TAG, "Session event: " + event.getEventName());
    }

    public interface Listener {
        void onStarted(String sessionId);

        void onPaused(String sessionId);

        void onResumed(String sessionId);

        void onStopped(String sessionId, int chunkCount);

        void onError(ScribeError error);

        void onCompleted(String sessionId, SessionResult result);

        void onFailed(String sessionId, ScribeError error);
    }
}
