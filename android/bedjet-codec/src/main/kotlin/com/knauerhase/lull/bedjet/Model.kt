package com.knauerhase.lull.bedjet

/** Operating modes. The codes match the BedJet 3 wire format; the V2 has its own codes, see [V2]. */
enum class Mode(val code: Int) {
    STANDBY(0),
    HEAT(1),
    TURBO(2),
    EXTENDED_HEAT(3),
    COOL(4),
    DRY(5),
    WAIT(6),
}

/** Status from a BedJet V2, decoded from a status packet. Temperatures are in half degrees Celsius. */
data class V2Status(
    val mode: Mode,
    /** 0..19. The fan runs at (fanStep + 1) * 5 percent. */
    val fanStep: Int,
    val actual: Int,
    val target: Int,
    val remainingSeconds: Int,
    val ledOn: Boolean,
    val muted: Boolean,
    /** 0 when no sequence is running. */
    val programStep: Int,
    val shutdownReason: Int,
) {
    val fanPercent: Int get() = (fanStep + 1) * 5
}

data class V2Info(
    val protocolVersion: Int,
    val firmware: Int,
    /** True when sequence steps use the longer format and the run command takes the time of day. */
    val extendedSequences: Boolean,
)

/** Status from a BedJet 3. The last four fields come from the second half of the status packet, when present. */
data class V3Status(
    val remainingSeconds: Int,
    val actual: Int,
    val target: Int,
    val mode: Mode,
    val fanStep: Int,
    val maxRuntimeMinutes: Int,
    val minTemperature: Int,
    val maxTemperature: Int,
    val ambient: Int,
    val shutdownReason: Int,
    val ledOn: Boolean? = null,
    val muted: Boolean? = null,
    val programStep: Int? = null,
    val notification: Int? = null,
)

/** One step of an on-device program, as the BedJet 3 stores it. */
data class V3Step(
    val mode: Mode,
    val fanStep: Int,
    val temperature: Int,
    val hours: Int,
    val minutes: Int,
    val endsAtClockTime: Boolean = false,
)

internal fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

internal fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

/** Half degrees Celsius the unit accepts for [Mode] on a BedJet 3, as the iOS app enforces them. */
val Mode.temperatureRange: IntRange
    get() = when (this) {
        Mode.HEAT -> 45..80
        Mode.TURBO -> 86..86
        Mode.EXTENDED_HEAT -> 38..67
        Mode.COOL -> 38..62
        Mode.DRY -> 40..80
        Mode.STANDBY, Mode.WAIT -> 80..80
    }

/** Longest a single run may last in this mode, in minutes. Turbo's 10 minutes is the unit's own safety timer. */
val Mode.maxMinutes: Int
    get() = when (this) {
        Mode.TURBO -> 10
        Mode.HEAT -> 240
        else -> 11 * 60 + 59
    }

/**
 * The range the unit accepts while running [mode]. A V2 only adjusts Heat, and holds Cool and Turbo at fixed targets.
 */
fun temperatureRange(mode: Mode, v2: Boolean): IntRange =
    if (!v2) {
        mode.temperatureRange
    } else {
        when (mode) {
            Mode.HEAT -> V2.HEAT_RANGE
            Mode.COOL -> 44..44
            Mode.TURBO -> 86..86
            else -> 80..80
        }
    }

/** False when the unit sets the temperature itself for [mode], so the app shows the fan speed instead. */
fun Mode.adjustsTemperature(v2: Boolean): Boolean = temperatureRange(this, v2).let { it.first != it.last }
