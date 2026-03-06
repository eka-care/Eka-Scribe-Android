package com.eka.scribesdk.api.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {

    @Test
    fun `IDLE can only transition to STARTING`() {
        assertTrue(SessionState.IDLE.canTransitionTo(SessionState.STARTING))
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.RECORDING))
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.COMPLETED))
    }

    @Test
    fun `STARTING can transition to RECORDING or ERROR`() {
        assertTrue(SessionState.STARTING.canTransitionTo(SessionState.RECORDING))
        assertTrue(SessionState.STARTING.canTransitionTo(SessionState.ERROR))
        assertFalse(SessionState.STARTING.canTransitionTo(SessionState.IDLE))
        assertFalse(SessionState.STARTING.canTransitionTo(SessionState.COMPLETED))
    }

    @Test
    fun `RECORDING can transition to PAUSED, STOPPING, or ERROR`() {
        assertTrue(SessionState.RECORDING.canTransitionTo(SessionState.PAUSED))
        assertTrue(SessionState.RECORDING.canTransitionTo(SessionState.STOPPING))
        assertTrue(SessionState.RECORDING.canTransitionTo(SessionState.ERROR))
        assertFalse(SessionState.RECORDING.canTransitionTo(SessionState.IDLE))
        assertFalse(SessionState.RECORDING.canTransitionTo(SessionState.COMPLETED))
    }

    @Test
    fun `PAUSED can transition to RECORDING or STOPPING`() {
        assertTrue(SessionState.PAUSED.canTransitionTo(SessionState.RECORDING))
        assertTrue(SessionState.PAUSED.canTransitionTo(SessionState.STOPPING))
        assertFalse(SessionState.PAUSED.canTransitionTo(SessionState.IDLE))
        assertFalse(SessionState.PAUSED.canTransitionTo(SessionState.ERROR))
    }

    @Test
    fun `STOPPING can transition to PROCESSING, COMPLETED, or ERROR`() {
        assertTrue(SessionState.STOPPING.canTransitionTo(SessionState.PROCESSING))
        assertTrue(SessionState.STOPPING.canTransitionTo(SessionState.COMPLETED))
        assertTrue(SessionState.STOPPING.canTransitionTo(SessionState.ERROR))
        assertFalse(SessionState.STOPPING.canTransitionTo(SessionState.IDLE))
    }

    @Test
    fun `PROCESSING can transition to COMPLETED or ERROR`() {
        assertTrue(SessionState.PROCESSING.canTransitionTo(SessionState.COMPLETED))
        assertTrue(SessionState.PROCESSING.canTransitionTo(SessionState.ERROR))
        assertFalse(SessionState.PROCESSING.canTransitionTo(SessionState.IDLE))
    }

    @Test
    fun `COMPLETED can transition to IDLE`() {
        assertTrue(SessionState.COMPLETED.canTransitionTo(SessionState.IDLE))
        assertFalse(SessionState.COMPLETED.canTransitionTo(SessionState.RECORDING))
    }

    @Test
    fun `ERROR can transition to IDLE`() {
        assertTrue(SessionState.ERROR.canTransitionTo(SessionState.IDLE))
        assertFalse(SessionState.ERROR.canTransitionTo(SessionState.RECORDING))
    }
}
