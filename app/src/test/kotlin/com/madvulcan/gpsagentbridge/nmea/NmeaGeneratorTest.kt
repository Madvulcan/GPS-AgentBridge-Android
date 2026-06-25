package com.madvulcan.gpsagentbridge.nmea

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for [NmeaGenerator]. These verify:
 *  - Field formatting (lat/lon conversion, time/date)
 *  - Checksum correctness (XOR of bytes between $ and *)
 *  - Sentence structure (talker ID, field count)
 *  - Round-trip parseability by gpsd's expected format
 *
 * Reference sentences used for comparison come from NMEA 0183 v4.10 + gpsd docs.
 */
class NmeaGeneratorTest {

    @Test
    fun `formatLatitude converts decimal degrees to ddmm format`() {
        // 35.9778° N → 35° + 0.9778*60 = 58.668 → "3558.6680,N"
        val out = NmeaGenerator.formatLatitude(35.9778)
        assertThat(out).isEqualTo("3558.6680,N")
    }

    @Test
    fun `formatLatitude handles southern hemisphere`() {
        val out = NmeaGenerator.formatLatitude(-33.8688)
        assertThat(out).endsWith("S")
        assertThat(out).isEqualTo("3352.1280,S")
    }

    @Test
    fun `formatLongitude converts decimal degrees to dddmm format`() {
        // -83.9219° W → 83° + 0.9219*60 = 55.3140 → "08355.3140,W"
        val out = NmeaGenerator.formatLongitude(-83.9219)
        assertThat(out).isEqualTo("08355.3140,W")
    }

    @Test
    fun `formatLongitude handles eastern hemisphere`() {
        val out = NmeaGenerator.formatLongitude(2.3522)
        assertThat(out).endsWith("E")
        assertThat(out).isEqualTo("00221.1320,E")
    }

    @Test
    fun `formatTime produces hhmmss_sss in UTC`() {
        // Pick a known UTC instant: 2024-06-23 21:35:11.000 UTC
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2024, Calendar.JUNE, 23, 21, 35, 11)
            set(Calendar.MILLISECOND, 0)
        }
        val out = NmeaGenerator.formatTime(cal.timeInMillis)
        assertThat(out).isEqualTo("213511.000")
    }

    @Test
    fun `formatDate produces ddmmyy in UTC`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2024, Calendar.JUNE, 23, 21, 35, 11)
        }
        val out = NmeaGenerator.formatDate(cal.timeInMillis)
        assertThat(out).isEqualTo("230624")
    }

    @Test
    fun `checksum is XOR of body bytes`() {
        // Known reference: GPGGA sample from the requirements doc.
        val body = "GPGGA,213511.000,3558.6686,N,08355.3128,W,1,08,0.9,299.0,M,-31.6,M,,"
        val cs = NmeaGenerator.checksum(body)
        // The doc claims the checksum is *47 — let's verify.
        assertThat(cs).isEqualTo("67")
    }

    @Test
    fun `formatGga produces a well-formed sentence`() {
        val fix = sampleFix()
        val gga = NmeaGenerator.formatGga(fix)
        assertThat(gga).startsWith("\$GPGGA,")
        assertThat(gga).contains("*")
        assertThat(gga).contains("M,,")
        // Verify the checksum matches.
        val body = gga.removePrefix("\$").substringBefore("*")
        val expected = NmeaGenerator.checksum(body)
        assertThat(gga).endsWith("*$expected")
    }

    @Test
    fun `formatRmc produces valid status A and FAA mode A`() {
        val fix = sampleFix()
        val rmc = NmeaGenerator.formatRmc(fix)
        assertThat(rmc).startsWith("\$GPRMC,")
        // status field is the 2nd field after $xxRMC,
        val fields = rmc.removePrefix("\$").substringBefore("*").split(",")
        assertThat(fields[2]).isEqualTo("A") // status
        assertThat(fields.last()).isEqualTo("A") // FAA mode
    }

    @Test
    fun formatRmcConvertsSpeedFromMsToKnots() {
        val fix = sampleFix(speed = 10f) // 10 m/s ≈ 19.4384 knots
        val rmc = NmeaGenerator.formatRmc(fix)
        val fields = rmc.removePrefix("\$").substringBefore("*").split(",")
        assertThat(fields[7]).isEqualTo("19.44")
    }

    @Test
    fun `formatGsa emits auto mode and 3D fix when altitude present`() {
        val fix = sampleFix()
        val gsa = NmeaGenerator.formatGsa(fix)
        val fields = gsa.removePrefix("\$").substringBefore("*").split(",")
        assertThat(fields[1]).isEqualTo("A")   // auto
        assertThat(fields[2]).isEqualTo("3")   // 3D fix
    }

    @Test
    fun `generate produces three sentences joined with CRLF`() {
        val out = NmeaGenerator.generate(sampleFix())
        val lines = out.trim().split("\r\n")
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).startsWith("\$GPGGA,")
        assertThat(lines[1]).startsWith("\$GPGSA,")
        assertThat(lines[2]).startsWith("\$GPRMC,")
    }

    @Test
    fun `generate ends with CRLF`() {
        val out = NmeaGenerator.generate(sampleFix())
        assertThat(out.endsWith("\r\n")).isTrue()
    }

    @Test
    fun `generate output is ASCII-only`() {
        val out = NmeaGenerator.generate(sampleFix())
        for (c in out) {
            assertThat(c.code).isLessThan(128)
        }
    }

    // ------------------------------------------------------------- helpers

    private fun sampleFix(
        lat: Double = 35.9778,
        lon: Double = -83.9219,
        alt: Double = 295.0,
        speed: Float = 0f,
        bearing: Float = 0f,
    ): GpsFix {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2024, Calendar.JUNE, 23, 21, 35, 11)
        }
        return GpsFix(
            latitude = lat,
            longitude = lon,
            altitudeMeters = alt,
            accuracyMeters = 12f,
            speedMetersPerSec = speed,
            bearingDegrees = bearing,
            timestampMillis = cal.timeInMillis,
            satellitesUsed = 8,
            hdop = 0.9f,
            vdop = 1.2f,
            pdop = 1.5f,
        )
    }
}
