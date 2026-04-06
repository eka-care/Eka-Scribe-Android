package com.eka.voice2rx

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal AccessibilityService for POC.
 * Its presence as an enabled accessibility service grants the app
 * privileged audio capture priority — allowing concurrent mic access
 * with AudioSource.VOICE_RECOGNITION during active phone calls.
 */
class ScribeAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */
    }

    override fun onInterrupt() { /* no-op */
    }
}
