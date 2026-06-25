package com.madvulcan.gpsagentbridge.nmea

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * A normalized GPS fix used to generate NMEA sentences. Decoupled from Android's
 * [android.location.Location] so this module is pure-Kotlin and unit-testable.
 *
 * Field semantics mirror NMEA 0183 GGA/GSA/RMC.
 */
data class GpsFix(
    val latitude: Double,        // decimal degrees, -90..90
    val longitude: Double,       // decimal degrees, -180..180
    val altitudeMeters: Double,  // meters above mean sea level
    val accuracyMeters: Float,   // horizontal accuracy estimate (Android Location.getAccuracy())
    val speedMetersPerSec: Float,
    val bearingDegrees: Float,
    val timestampMillis: Long,
    val satellitesUsed: Int = 8,
    val hdop: Float = 1.0f,
    val vdop: Float = 1.0f,
    val pdop: Float = 1.5f,
    val fixQuality: Int = 1,     // GGA: 0=no fix, 1=GPS, 2=DGPS
)

/**
 * Generates NMEA 0183 sentences from a [GpsFix].
 *
 * Output per call is the three-sentence block:
 *   $GPGGA,...
 *   $GPGSA,...
 *   $GPRMC,...
 *
 * All three are joined with `\r\n` and the block is terminated with `\r\n`,
 * so the resulting string is suitable as a single UDP datagram payload.
 *
 * Talker ID is hardcoded to `GP` (GPS-only) for maximum gpsd compatibility.
 * gpsd accepts `GN` (multi-constellation) as well, but `GP` is the safer default.
 *
 * Reference: NMEA 0183 v4.10, plus https://gpsd.gitlab.io/gpsd/NMEA.html
 */
object NmeaGenerator {

    /**
     * Generate a full GGA + GSA + RMC block for the given fix.
     * Returns the wire-format string ready to send via UDP.
     */
    fun generate(fix: GpsFix): String {
        val gga = formatGga(fix)
        val gsa = formatGsa(fix)
        val rmc = formatRmc(fix)
        return buildString {
            append(gga).append("\r\n")
            append(gsa).append("\r\n")
            append(rmc).append("\r\n")
        }
    }

    // ----------------------------------------------------------------- GGA

    /**
     * $GPGGA,time,lat,N/S,lon,E/W,quality,sats,hdop,alt,M,geoidsep,M,age,refid*cs
     *
     * quality: 0=no fix, 1=GPS, 2=DGPS. We always emit 1 from a valid fix.
     */
    fun formatGga(fix: GpsFix): String {
        val time = formatTime(fix.timestampMillis)
        val lat = formatLatitude(fix.latitude)
        val lon = formatLongitude(fix.longitude)
        val alt = formatFixed(fix.altitudeMeters, 1)
        val sats = "%02d".format(fix.satellitesUsed.coerceIn(0, 99))
        val hdop = formatFixed(fix.hdop.toDouble(), 1)
        // geoid separation — Android doesn't expose this; emit empty.
        val body = "GPGGA,$time,$lat,$lon,${fix.fixQuality},$sats,$hdop,$alt,M,,M,,"
        return "\$$body*${checksum(body)}"
    }

    // ----------------------------------------------------------------- GSA

    /**
     * $GPGSA,mode,fixType,sat01..sat12,pdop,hdop,vdop*cs
     *
     * mode: M=manual, A=automatic 2D/3D
     * fixType: 1=no fix, 2=2D, 3=3D
     *
     * We don't track per-satellite PRNs (Android's Location API doesn't expose them
     * conveniently), so the 12 PRN slots are left empty. gpsd accepts this and still
     * uses the DOP values.
     */
    fun formatGsa(fix: GpsFix): String {
        val fixType = if (fix.altitudeMeters.isNaN()) 2 else 3
        val pdop = formatFixed(fix.pdop.toDouble(), 1)
        val hdop = formatFixed(fix.hdop.toDouble(), 1)
        val vdop = formatFixed(fix.vdop.toDouble(), 1)
        val body = "GPGSA,A,$fixType,,,,,,,,,,,,$pdop,$hdop,$vdop"
        return "\$$body*${checksum(body)}"
    }

    // ----------------------------------------------------------------- RMC

    /**
     * $GPRMC,time,status,lat,N/S,lon,E/W,speed_knots,cearing,date,magvar,E/W,mode*cs
     *
     * status: A=valid, V=invalid
     * speed: knots (m/s × 1.943844)
     * mode (FAA): A=autonomous, D=differential, E=estimated, N=not valid
     *
     * Per the requirements doc, the trailing mode letter is the FAA mode indicator
     * (NOT the GGA quality field). We always emit 'A' (autonomous) for a valid fix.
     */
    fun formatRmc(fix: GpsFix): String {
        val time = formatTime(fix.timestampMillis)
        val date = formatDate(fix.timestampMillis)
        val lat = formatLatitude(fix.latitude)
        val lon = formatLongitude(fix.longitude)
        val speedKnots = formatFixed(fix.speedMetersPerSec * 1.943844, 2)
        val bearing = formatFixed(fix.bearingDegrees.toDouble(), 2)
        val body = "GPRMC,$time,A,$lat,$lon,$speedKnots,$bearing,$date,,,A"
        return "\$$body*${checksum(body)}"
    }

    // -------------------------------------------------------------- helpers

    /** hhmmss.sss in UTC from epoch-millis. */
    fun formatTime(epochMillis: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = epochMillis
        }
        val hh = "%02d".format(c.get(Calendar.HOUR_OF_DAY))
        val mm = "%02d".format(c.get(Calendar.MINUTE))
        val ss = "%02d".format(c.get(Calendar.SECOND))
        val ms = "%03d".format(c.get(Calendar.MILLISECOND))
        return "$hh$mm$ss.$ms"
    }

    /** ddmmyy in UTC from epoch-millis. */
    fun formatDate(epochMillis: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = epochMillis
        }
        val dd = "%02d".format(c.get(Calendar.DAY_OF_MONTH))
        val mm = "%02d".format(c.get(Calendar.MONTH) + 1)
        val yy = "%02d".format(c.get(Calendar.YEAR) % 100)
        return "$dd$mm$yy"
    }

    /**
     * Latitude: ddmm.mmmm,N|S
     * - Latitude 35.9778 → 3558.6680,N
     */
    fun formatLatitude(deg: Double): String {
        val hemi = if (deg < 0) "S" else "N"
        val absDeg = abs(deg)
        val d = absDeg.toInt()
        val m = (absDeg - d) * 60.0
        return "%02d%07.4f,$hemi".format(d, m)
    }

    /**
     * Longitude: dddmm.mmmm,E|W
     * - Longitude -83.9219 → 08355.3140,W
     */
    fun formatLongitude(deg: Double): String {
        val hemi = if (deg < 0) "W" else "E"
        val absDeg = abs(deg)
        val d = absDeg.toInt()
        val m = (absDeg - d) * 60.0
        return "%03d%07.4f,$hemi".format(d, m)
    }

    /** Format a double with [decimals] digits, never in scientific notation. */
    private fun formatFixed(value: Double, decimals: Int): String {
        if (value.isNaN()) return "0." + "0".repeat(decimals)
        return "%.${decimals}f".format(value)
    }

    /**
     * NMEA 0183 checksum: XOR of every byte between (but not including) `$` and `*`.
     */
    fun checksum(body: String): String {
        var cs = 0
        for (c in body) cs = cs xor c.code
        return "%02X".format(cs)
    }
}
