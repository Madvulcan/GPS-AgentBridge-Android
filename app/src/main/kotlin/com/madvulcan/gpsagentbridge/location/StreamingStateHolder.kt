package com.madvulcan.gpsagentbridge.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide singleton that holds the *currently active* [TransmissionEngine].
 *
 * Why this exists:
 *  - The foreground service creates a [TransmissionEngine] when streaming starts.
 *  - The Compose UI (via [com.madvulcan.gpsagentbridge.ui.StreamingViewModel]) needs
 *    to read the live state of THAT engine — not a separate shadow instance.
 *  - Both the service and the ViewModel are Hilt-scoped, so they can't directly share
 *    an instance via DI without awkwardly widening the scope.
 *
 * The fix: the service calls [bind] when it starts streaming and [unbind] when it stops.
 * The ViewModel reads from [activeState] which is the bound engine's state, or a default
 * idle state when no engine is bound.
 *
 * This is simpler than binding to the service for v0.1. A future refactor could replace
 * this with a proper service binder.
 */
object StreamingStateHolder {

    private val _activeState = MutableStateFlow(TransmissionState())
    val activeState: StateFlow<TransmissionState> = _activeState.asStateFlow()

    private var boundEngine: TransmissionEngine? = null

    /**
     * Wire the holder to forward the given engine's state to [_activeState].
     * Call this when the foreground service starts streaming.
     */
    fun bind(engine: TransmissionEngine) {
        boundEngine = engine
        // Seed with current state immediately so the UI shows the right thing on bind.
        _activeState.value = engine.state.value
    }

    /**
     * Stop forwarding. Resets [_activeState] to idle. Call this when the foreground
     * service stops streaming.
     */
    fun unbind() {
        boundEngine = null
        _activeState.value = TransmissionState(status = StreamStatus.IDLE)
    }

    /**
     * Pull a fresh state snapshot from the bound engine. Called by the service after
     * every state-mutating operation (onFix, transmit, etc.) so the holder's flow
     * reflects the latest.
     *
     * This is a polling-style update — the service calls it from its `collectLatest`
     * on the engine's state. Cleaner than threading the engine's StateFlow through
     * this singleton.
     */
    fun publish(state: TransmissionState) {
        _activeState.value = state
    }
}
