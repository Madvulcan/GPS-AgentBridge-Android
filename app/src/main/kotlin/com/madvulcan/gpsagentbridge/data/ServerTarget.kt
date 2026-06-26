package com.madvulcan.gpsagentbridge.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One destination server for NMEA UDP transmission.
 *
 * - [id] is a unique identifier assigned on creation, stable across edits.
 *   Used as the Compose remember key so text field state survives recomposition.
 * - [host] is a hostname or IP literal. Examples: `100.x.x.x`, `gpsd.lan`,
 *   `192.168.1.50`.
 * - [port] is the UDP port on the destination (default 2948 — gpsd's NMEA ingest port).
 * - [label] is an optional human-readable name shown in the UI ("Home desktop",
 *   "VPS relay", etc.). May be blank.
 *
 * Stored as part of a JSON array in DataStore.
 */
@Serializable
data class ServerTarget(
    val id: String = UUID.randomUUID().toString(),
    val host: String,
    val port: Int = 2948,
    val label: String = "",
) {
    /**
     * Convenience display string. Prefers the label when set, otherwise falls back
     * to host:port. The host:port is always shown in the secondary line of the UI
     * regardless, so this is just for the primary line.
     */
    val displayName: String get() = label.ifBlank { "$host:$port" }

    /** Stable identifier for list keys / Compose remember tracking. */
    val stableId: String get() = id
}
