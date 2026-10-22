package com.knauerhase.lull.bedjet

/**
 * BedJet 3 commands and status. Commands are written to GATT characteristic 0x2004, and status arrives
 * as notifications on 0x2000. See PROTOCOL.md in bedjet-protocol/ for the full table.
 */
object V3 {
    val ACK_NOTIFICATION: ByteArray = bytes(0x01, 0x52)

    fun buttonCode(mode: Mode): Int? = when (mode) {
        Mode.STANDBY -> 0x01
        Mode.COOL -> 0x02
        Mode.HEAT -> 0x03
        Mode.TURBO -> 0x04
        Mode.DRY -> 0x05
        Mode.EXTENDED_HEAT -> 0x06
        else -> null
    }

    fun setMode(mode: Mode): ByteArray? = buttonCode(mode)?.let { bytes(0x01, it) }

    fun setRuntime(minutes: Int): ByteArray = bytes(0x02, minutes / 60, minutes % 60)

    fun setTemperature(halfDegrees: Int): ByteArray = bytes(0x03, halfDegrees and 0xFF)

    fun setFan(step: Int): ByteArray = bytes(0x07, step.coerceIn(0, 19))

    fun setClock(hour: Int, minute: Int): ByteArray = bytes(0x08, hour, minute)

    fun setLed(on: Boolean): ByteArray = bytes(0x01, if (on) 0x46 else 0x47)

    fun setMuted(muted: Boolean): ByteArray = bytes(0x01, if (muted) 0x48 else 0x49)

    fun startProgram(slot: Int): ByteArray = bytes(0x01, 0x80 + slot)

    fun deleteProgram(slot: Int): ByteArray = bytes(0x01, 0x83 + slot)

    /** A 19-byte command: 0x40, slot, 0x10, then up to 15 printable ASCII bytes, zero padded. */
    fun nameProgram(slot: Int, name: String): ByteArray {
        val ascii = name.filter { it.code in 32..126 }.take(15).toByteArray(Charsets.US_ASCII)
        return bytes(0x40, 4 + slot, 0x10) + ascii + ByteArray(16 - ascii.size)
    }

    /** One 43-byte write per eight steps. Every write carries the program's total step count. */
    fun writeProgram(slot: Int, steps: List<V3Step>): List<ByteArray> {
        val fragments = maxOf(1, (steps.size + 7) / 8)
        return (0 until fragments).map { index ->
            val body = ByteArray(43)
            body[0] = 0x40
            body[1] = (7 + slot * 4 + index).toByte()
            body[2] = steps.size.toByte()
            steps.drop(index * 8).take(8).forEachIndexed { i, step ->
                val base = 3 + i * 5
                body[base] = step.mode.code.toByte()
                body[base + 1] = step.fanStep.coerceIn(0, 19).toByte()
                body[base + 2] = step.temperature.toByte()
                body[base + 3] = ((step.hours and 0x7F) or (if (step.endsAtClockTime) 0x80 else 0)).toByte()
                body[base + 4] = step.minutes.toByte()
            }
            body
        }
    }

    fun parseStatus(packet: ByteArray): V3Status? {
        if (packet.size < 20) return null
        val mode = Mode.entries.firstOrNull { it.code == packet.u(9) } ?: return null
        val full = packet.size >= 30
        return V3Status(
            remainingSeconds = packet.u(4) * 3600 + packet.u(5) * 60 + packet.u(6),
            actual = packet.u(7),
            target = packet.u(8),
            mode = mode,
            fanStep = packet.u(10),
            maxRuntimeMinutes = packet.u(11) * 60 + packet.u(12),
            minTemperature = packet.u(13),
            maxTemperature = packet.u(14),
            ambient = packet.u(17),
            shutdownReason = packet.u(18),
            ledOn = if (full) (packet.u(27) and 0x10) != 0 else null,
            muted = if (full) (packet.u(27) and 0x01) != 0 else null,
            programStep = if (full) packet.u(28) else null,
            notification = if (full) packet.u(29) else null,
        )
    }
}
