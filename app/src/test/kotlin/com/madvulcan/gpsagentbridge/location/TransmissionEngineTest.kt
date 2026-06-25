package com.madvulcan.gpsagentbridge.location

import com.google.common.truth.Truth.assertThat
import com.madvulcan.gpsagentbridge.data.ServerTarget
import com.madvulcan.gpsagentbridge.data.Settings
import com.madvulcan.gpsagentbridge.net.SendResult
import com.madvulcan.gpsagentbridge.net.UdpSender
import com.madvulcan.gpsagentbridge.nmea.GpsFix
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [TransmissionEngine]. These cover the core §4.1 trigger logic
 * from the requirements doc:
 *  - accuracy gate rejects bad fixes
 *  - first fix always transmits
 *  - distance threshold triggers transmission
 *  - max interval triggers transmission
 *  - dry-run mode still updates state but skips the network
 *  - per-target success/failure tracking
 *
 * Uses a fake [UdpSender] so no actual sockets are opened.
 */
class TransmissionEngineTest {

    @Test
    fun `first fix always transmits`() = runTest {
        val engine = TransmissionEngine(sender = FakeSender(ok = true))
        engine.setSettings(defaultSettings())
        engine.startStreaming()

        val didSend = engine.onFix(fixAt(35.0, -83.0))
        assertThat(didSend).isTrue()
        assertThat(engine.state.value.transmissionsToday).isEqualTo(1)
    }

    @Test
    fun `fix with accuracy worse than threshold is rejected and not counted as tx`() = runTest {
        val engine = TransmissionEngine(sender = FakeSender(ok = true))
        engine.setSettings(defaultSettings(minAccuracyMeters = 20))
        engine.startStreaming()

        val badFix = fixAt(35.0, -83.0, accuracy = 100f)
        val didSend = engine.onFix(badFix)
        assertThat(didSend).isFalse()
        assertThat(engine.state.value.transmissionsToday).isEqualTo(0)
        assertThat(engine.state.value.fixesRejectedAccuracy).isEqualTo(1)
    }

    @Test
    fun `fix within distance threshold does not transmit`() = runTest {
        val engine = TransmissionEngine(sender = FakeSender(ok = true))
        engine.setSettings(defaultSettings(distanceThresholdMeters = 500))
        engine.startStreaming()
        engine.onFix(fixAt(35.0, -83.0))

        // Move 100m east — below 500m threshold.
        val moved = fixAt(35.0, -82.999) // ~90m at this latitude
        val didSend = engine.onFix(moved)
        assertThat(didSend).isFalse()
        assertThat(engine.state.value.transmissionsToday).isEqualTo(1) // only the first
    }

    @Test
    fun `fix beyond distance threshold transmits`() = runTest {
        val engine = TransmissionEngine(sender = FakeSender(ok = true))
        engine.setSettings(defaultSettings(distanceThresholdMeters = 500))
        engine.startStreaming()
        engine.onFix(fixAt(35.0, -83.0))

        // Move ~1km east.
        val moved = fixAt(35.0, -82.99)
        val didSend = engine.onFix(moved)
        assertThat(didSend).isTrue()
        assertThat(engine.state.value.transmissionsToday).isEqualTo(2)
    }

    @Test
    fun `max interval triggers transmission even without movement`() = runTest {
        var clock = 1_000_000L
        val engine = TransmissionEngine(
            sender = FakeSender(ok = true),
            now = { clock },
        )
        engine.setSettings(defaultSettings(maxIntervalMinutes = 5, distanceThresholdMeters = 5000))
        engine.startStreaming()
        engine.onFix(fixAt(35.0, -83.0))
        assertThat(engine.state.value.transmissionsToday).isEqualTo(1)

        // Advance 6 minutes — beyond max interval.
        clock += 6 * 60_000L
        val didSend = engine.onFix(fixAt(35.0, -83.0)) // same location
        assertThat(didSend).isTrue()
        assertThat(engine.state.value.transmissionsToday).isEqualTo(2)
    }

    @Test
    fun `dry run updates state but does not call sender`() = runTest {
        val sender = FakeSender(ok = true)
        val engine = TransmissionEngine(sender = sender)
        engine.setSettings(defaultSettings(dryRun = true))
        engine.startStreaming()
        engine.onFix(fixAt(35.0, -83.0))

        assertThat(engine.state.value.transmissionsToday).isEqualTo(1)
        assertThat(sender.callCount.get()).isEqualTo(0) // dry run = no network
    }

    @Test
    fun `per-target failure tracked in state`() = runTest {
        val targets = listOf(
            ServerTarget(host = "good.example", port = 2948),
            ServerTarget(host = "bad.example", port = 2948),
        )
        val sender = FakeSender(okFor = mapOf(
            "good.example" to true,
            "bad.example" to false,
        ))
        val engine = TransmissionEngine(sender = sender)
        engine.setSettings(defaultSettings(targets = targets))
        engine.startStreaming()
        engine.onFix(fixAt(35.0, -83.0))

        val stats = engine.state.value.perTargetStats
        assertThat(stats).hasSize(2)
        assertThat(stats.values.count { it.lastError != null }).isEqualTo(1)
    }

    @Test
    fun `distanceMeters uses haversine`() = runTest {
        val a = fixAt(35.9778, -83.9219)
        val b = fixAt(35.9780, -83.9219)
        val d = TransmissionEngine.distanceMeters(a, b)
        // ~22 meters apart at this latitude
        assertThat(d).isAtLeast(20f)
        assertThat(d).isAtMost(25f)
    }

    // ------------------------------------------------------------- helpers

    private fun defaultSettings(
        distanceThresholdMeters: Int = 500,
        maxIntervalMinutes: Int = 10,
        minAccuracyMeters: Int = 20,
        targets: List<ServerTarget> = listOf(ServerTarget("127.0.0.1", 2948)),
        dryRun: Boolean = false,
    ) = Settings(
        targets = targets,
        distanceThresholdMeters = distanceThresholdMeters,
        maxIntervalMinutes = maxIntervalMinutes,
        minAccuracyMeters = minAccuracyMeters,
        dryRun = dryRun,
    )

    private fun fixAt(
        lat: Double,
        lon: Double,
        accuracy: Float = 5f,
    ): GpsFix = GpsFix(
        latitude = lat,
        longitude = lon,
        altitudeMeters = 300.0,
        accuracyMeters = accuracy,
        speedMetersPerSec = 0f,
        bearingDegrees = 0f,
        timestampMillis = 1_000_000L,
    )

    /** Fake [UdpSender] — records calls, returns configured results per host. */
    private class FakeSender(
        var ok: Boolean = true,
        private val okFor: Map<String, Boolean> = emptyMap(),
    ) : UdpSender() {
        val callCount = AtomicInteger(0)

        override suspend fun sendToAll(
            payload: ByteArray,
            targets: List<ServerTarget>,
        ): List<SendResult> {
            callCount.incrementAndGet()
            return targets.map { t ->
                val okFlag = okFor[t.host] ?: ok
                if (okFlag) SendResult(target = t, bytesSent = payload.size)
                else SendResult(target = t, bytesSent = 0, error = RuntimeException("fake fail"))
            }
        }
    }
}
