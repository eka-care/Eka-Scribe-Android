package com.eka.scribesdk.session

/** Why the session is paused; only the automatic ones get an explanation. */
internal enum class PauseReason { USER, AUDIO_FOCUS, MIC_IN_USE, NO_RESPONSE }
