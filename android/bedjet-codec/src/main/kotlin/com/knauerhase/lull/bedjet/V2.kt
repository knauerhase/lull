package com.knauerhase.lull.bedjet

import kotlin.random.Random

/**
 * BedJet V2 protocol: serial packets sent through a Microchip BLE UART bridge.
 *
 * Packet layout: `prefix, length, type, payload..., checksum`. The phone sends prefix 0x58 and the unit
 * answers with 0x59. `length` counts type and payload, and the checksum makes every byte sum to 0xff.
 * See docs/BEDJET_V2_PROTOCOL.md in the Lull repository.
 */
object V2 {
    const val COMMAND_PREFIX = 0x58
    const val REPLY_PREFIX = 0x59

    /** Oldest firmware that runs sequences. */
    const val SEQUENCE_FIRMWARE = 48

    /** The unit reports the running step in five bits, so a sequence can't be longer than this. */
    const val MAX_STEPS = 31

    /** Heat targets the unit accepts, in half degrees Celsius. */
    val HEAT_RANGE = 44..80

    // MARK: Framing

    fun checksum(body: ByteArray): Byte {
        var sum = 0
        for (b in body) sum += b.toInt() and 0xFF
        return (sum.inv() and 0xFF).toByte()
    }

    fun frame(kind: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val body = bytes(COMMAND_PREFIX, payload.size + 1, kind) + payload
        return body + checksum(body)
    }

    /**
     * Splits a notification byte stream into packets. Returns the complete packets and the leftover bytes,
     * which belong at the front of the next notification. Bytes that can't start a packet are dropped.
     */
    fun takePackets(buffer: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var start = 0
        var rest = buffer.size
        while (true) {
            val prefix = (start until buffer.size).firstOrNull { buffer[it] == REPLY_PREFIX.toByte() } ?: break
            if (prefix + 1 >= buffer.size) {
                rest = prefix
                break
            }
            val length = buffer.u(prefix + 1) + 3
            // Real packets are short, so a long length means this 0x59 was data, not a prefix.
            if (length <= 3 || length > 64) {
                start = prefix + 1
                continue
            }
            if (prefix + length > buffer.size) {
                rest = prefix
                break
            }
            val packet = buffer.copyOfRange(prefix, prefix + length)
            if (checksum(packet.copyOfRange(0, length - 1)) == packet[length - 1]) {
                packets += packet
                start = prefix + length
            } else {
                start = prefix + 1
            }
        }
        return packets to buffer.copyOfRange(rest, buffer.size)
    }

    // MARK: Commands

    fun infoRequest(): ByteArray = frame(0x0B)

    fun nameRequest(): ByteArray = frame(0x06, bytes(0x02, 16))

    /**
     * Modes are buttons: pressing one switches to that mode, and pressing the current one turns the air off.
     * Returns null when pressing would do nothing, or the mode isn't one the V2 has.
     */
    fun button(mode: Mode, current: Mode = Mode.STANDBY): ByteArray? {
        if (mode == current) return null
        val target = if (mode == Mode.STANDBY) current else mode
        val code = when (target) {
            Mode.TURBO -> 1
            Mode.HEAT -> 2
            Mode.COOL -> 3
            else -> return null
        }
        return frame(0x01, bytes(code))
    }

    fun settings(ledOn: Boolean, muted: Boolean): ByteArray =
        frame(0x11, bytes((if (muted) 0x01 else 0) or (if (ledOn) 0 else 0x02)))

    /**
     * Temperature, fan and timer go out together, so anything not being changed is repeated from [status].
     */
    fun scene(
        status: V2Status,
        temperature: Int? = null,
        fanStep: Int? = null,
        seconds: Int? = null,
    ): ByteArray {
        val remaining = maxOf(seconds ?: status.remainingSeconds, 0)
        val target = (temperature ?: status.target).coerceIn(0, 0x7F)
        val fan = (fanStep ?: status.fanStep).coerceIn(0, 19)
        return frame(
            0x0E,
            bytes(
                statusCode(status.mode),
                fan + 1,
                target or (if (status.muted) 0x80 else 0),
                minOf(remaining / 3600, 12),
                (remaining / 60) % 60,
                remaining % 60,
            ),
        )
    }

    /** Empties the unit's sequence before new steps are written. */
    fun clearSequence(): ByteArray = frame(0x0C)

    /**
     * One step of the sequence, written to the parameter for its position. Units on the newer format take one
     * more byte. Throws if the V2 doesn't have [mode].
     */
    fun writeStep(
        mode: Mode,
        fanStep: Int,
        temperature: Int,
        minutes: Int,
        index: Int,
        extended: Boolean,
    ): ByteArray {
        val code = when (mode) {
            Mode.WAIT -> 0
            Mode.TURBO, Mode.HEAT, Mode.COOL -> statusCode(mode)
            else -> throw IllegalArgumentException(UNSUPPORTED_MODES)
        }
        // The official app stores fixed targets for Cool and Turbo/Wait, and the unit ignores them.
        val target = when (mode) {
            Mode.HEAT -> temperature.coerceIn(HEAT_RANGE)
            Mode.COOL -> 40
            else -> 80
        }
        val payload = mutableListOf(
            0x80 + index,
            if (extended) 7 else 6,
            code,
            fanStep.coerceIn(0, 19) + 1,
            minutes / 60,
            minutes % 60,
            0,
            target,
        )
        if (extended) payload += 0
        return frame(0x05, bytes(*payload.toIntArray()))
    }

    /** Ten bytes the official app stores with a sequence. Any ten bytes work; the app uses random ones. */
    fun tagSequence(tag: ByteArray = ByteArray(8) { Random.nextInt(256).toByte() }): ByteArray {
        val id = tag.copyOf(minOf(tag.size, 10))
        return frame(0x05, bytes(0x03, 0x0A) + id + ByteArray(10 - id.size))
    }

    /** Starts the stored sequence. Newer units also take the time of day. */
    fun runSequence(timeOfDay: Triple<Int, Int, Int>?, extended: Boolean): ByteArray {
        if (!extended || timeOfDay == null) return frame(0x0D)
        val (hour, minute, second) = timeOfDay
        return frame(0x0D, bytes(hour, minute, second))
    }

    // MARK: Replies

    /** Payload of the reply to [query] (0x0b for info, 0x02 for name), or null when the packet is something else. */
    fun reply(query: Int, packet: ByteArray): ByteArray? {
        if (packet.size < 4 || packet[0] != REPLY_PREFIX.toByte()) return null
        if (query == 0x0B) {
            return if (packet.u(2) == 0x01) packet.copyOfRange(3, packet.size - 1) else null
        }
        if (packet.u(2) != 0x05 || packet.size < 5 || packet.u(3) != query) return null
        return packet.copyOfRange(4, packet.size - 1)
    }

    fun parseInfo(payload: ByteArray): V2Info? {
        if (payload.size < 2) return null
        val firmware = payload.u(1)
        val newerFormat = payload.size > 7 && (payload.u(7) and 0x01) != 0
        return V2Info(
            protocolVersion = payload.u(0),
            firmware = firmware,
            extendedSequences = newerFormat && firmware >= 53,
        )
    }

    /** Status packets: the unit sends these continuously. Returns null for anything that isn't one. */
    fun parseStatus(packet: ByteArray): V2Status? {
        if (packet.size < 14 || packet[0] != REPLY_PREFIX.toByte() || packet.u(2) != 0x02) return null
        val mode = when (packet.u(4) shr 5 and 0x07) {
            0 -> Mode.STANDBY
            1 -> Mode.TURBO
            2 -> Mode.HEAT
            3 -> Mode.COOL
            else -> return null
        }
        val hours = packet.u(5) shr 4
        val minutes = ((packet.u(5) shl 2) and 0x3C) or (packet.u(6) shr 6)
        val seconds = packet.u(6) and 0x3F
        return V2Status(
            mode = mode,
            fanStep = maxOf((packet.u(4) and 0x1F) - 1, 0),
            actual = packet.u(3) and 0x7F,
            target = packet.u(7) and 0x7F,
            remainingSeconds = hours * 3600 + minutes * 60 + seconds,
            ledOn = (packet.u(3) and 0x80) == 0,
            muted = (packet.u(8) and 0x80) != 0,
            programStep = (packet.u(10) shr 3) and 0x1F,
            shutdownReason = if (packet.u(1) > 10) packet.u(12) else 0,
        )
    }

    /** The unit's name. A unit that was never named answers with no bytes. */
    fun parseName(payload: ByteArray): String =
        payload.takeWhile { it != 0.toByte() }.toByteArray().decodeToString().trim()

    const val UNSUPPORTED_MODES = "A BedJet V2 doesn't have Gentle heat or Dry. Change those phases to Heat or Cool."

    private fun statusCode(mode: Mode): Int = when (mode) {
        Mode.TURBO -> 1
        Mode.HEAT -> 2
        Mode.COOL -> 3
        else -> 0
    }
}
