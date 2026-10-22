package com.knauerhase.lull.bedjet

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures from the Swift tests in BedJetKit, which come from captures of the official iOS app. */
class V2Test {
    private fun hex(text: String): ByteArray =
        text.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun commandsMatchCapturedBytes() {
        assertContentEquals(hex("58 01 0b 9b"), V2.infoRequest())
        assertContentEquals(hex("58 03 06 02 10 8c"), V2.nameRequest())
        assertContentEquals(hex("58 02 01 02 a2"), V2.button(Mode.HEAT, Mode.STANDBY))
        assertContentEquals(hex("58 02 01 01 a3"), V2.button(Mode.TURBO, Mode.COOL))
        assertContentEquals(hex("58 02 11 01 93"), V2.settings(ledOn = true, muted = true))
        assertContentEquals(hex("58 02 11 02 92"), V2.settings(ledOn = false, muted = false))
    }

    @Test
    fun modesAreButtonsThatToggle() {
        assertContentEquals(hex("58 02 01 03 a1"), V2.button(Mode.STANDBY, Mode.COOL))
        assertNull(V2.button(Mode.COOL, Mode.COOL))
        assertNull(V2.button(Mode.STANDBY, Mode.STANDBY))
        assertNull(V2.button(Mode.DRY, Mode.STANDBY))
    }

    @Test
    fun sceneMatchesCapturedTimerCommand() {
        val heat = status(Mode.HEAT, fanStep = 16, target = 76, remaining = 3 * 60)
        assertContentEquals(hex("58 07 0e 02 11 cc 00 14 00 9f"), V2.scene(heat, seconds = 20 * 60))
    }

    @Test
    fun sceneKeepsTimerWhenChangingFan() {
        val cool = status(Mode.COOL, fanStep = 14, target = 54, remaining = 9 * 3600 + 59 * 60 + 55)
        assertContentEquals(hex("58 07 0e 03 0c b6 09 3b 37 52"), V2.scene(cool, fanStep = 11))
    }

    @Test
    fun writesSequenceSteps() {
        assertContentEquals(
            hex("58 0a 05 81 07 02 08 00 1e 00 44 00 a4"),
            V2.writeStep(Mode.HEAT, fanStep = 7, temperature = 68, minutes = 30, index = 1, extended = true),
        )
        assertContentEquals(
            hex("58 09 05 80 06 03 0d 06 00 00 28 d5"),
            V2.writeStep(Mode.COOL, fanStep = 12, temperature = 44, minutes = 360, index = 0, extended = false),
        )
        assertFailsWith<IllegalArgumentException> {
            V2.writeStep(Mode.DRY, fanStep = 5, temperature = 60, minutes = 60, index = 0, extended = true)
        }
    }

    @Test
    fun runsSequence() {
        assertContentEquals(hex("58 04 0d 17 05 09 71"), V2.runSequence(Triple(23, 5, 9), extended = true))
        assertContentEquals(hex("58 01 0d 99"), V2.runSequence(Triple(23, 5, 9), extended = false))
    }

    @Test
    fun sequenceMatchesTheOfficialAppsBytes() {
        // Newer (extended) format, as the Swift test's firmware-53 unit gets.
        val commands = listOf(V2.clearSequence()) + listOf(
            V2.writeStep(Mode.TURBO, fanStep = 19, temperature = 86, minutes = 10, index = 0, extended = true),
            V2.writeStep(Mode.HEAT, fanStep = 7, temperature = 68, minutes = 30, index = 1, extended = true),
            V2.writeStep(Mode.COOL, fanStep = 12, temperature = 44, minutes = 360, index = 2, extended = true),
        )
        assertContentEquals(hex("58 01 0c 9a"), commands[0])
        assertContentEquals(hex("58 0a 05 80 07 01 14 00 0a 00 50 00 a2"), commands[1])
        assertContentEquals(hex("58 0a 05 81 07 02 08 00 1e 00 44 00 a4"), commands[2])
        assertContentEquals(hex("58 0a 05 82 07 03 0d 06 00 00 28 00 d1"), commands[3])
    }

    @Test
    fun parsesCapturedOffStatus() {
        val off = V2.parseStatus(hex("59 0b 02 a7 0c 00 00 41 00 00 00 00 00 a5"))!!
        assertEquals(Mode.STANDBY, off.mode)
        assertEquals(0x27, off.actual)
        assertEquals(0x41, off.target)
        assertEquals(0, off.remainingSeconds)
        assertEquals(false, off.ledOn)
        assertEquals(false, off.muted)
        assertEquals(0, off.programStep)
    }

    @Test
    fun parsesRunningStatus() {
        val cool = V2.parseStatus(hex("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 26"))!!
        assertEquals(Mode.COOL, cool.mode)
        assertEquals(60, cool.fanPercent)
        assertEquals(9 * 3600 + 59 * 60 + 52, cool.remainingSeconds)
        assertEquals(0x36, cool.target)
        assertTrue(cool.muted)
        assertTrue(cool.ledOn)
        assertEquals(1, cool.programStep)
        assertEquals(3, cool.shutdownReason)
    }

    @Test
    fun reassemblesPacketsAcrossNotifications() {
        val (first, rest1) = V2.takePackets(hex("00 ff 59 0b 02 34 6c 9e f4"))
        assertEquals(0, first.size)
        val (second, rest2) = V2.takePackets(rest1 + hex("b6 80 00 08 00 03 26 59 12 05 02 42 65 64"))
        assertContentEquals(hex("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 26"), second.single())
        val (third, rest3) = V2.takePackets(rest2 + hex("72 6f 6f 6d 20 20 20 20 20 20 20 20 20 a5"))
        assertEquals("Bedroom", V2.parseName(V2.reply(0x02, third.single())!!))
        assertEquals(0, rest3.size)
    }

    @Test
    fun dropsPacketsWithBadChecksums() {
        val (packets, rest) = V2.takePackets(hex("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 27"))
        assertEquals(0, packets.size)
        assertTrue(rest.size < 14)
    }

    @Test
    fun parsesInfo() {
        val packet = hex("59 0c 01 02 35 00 00 00 00 00 01 00 00 00 61")
        val info = V2.parseInfo(V2.reply(0x0B, packet)!!)
        assertEquals(V2Info(protocolVersion = 2, firmware = 53, extendedSequences = true), info)
        assertNull(V2.reply(0x02, packet))
    }

    private fun status(mode: Mode, fanStep: Int, target: Int, remaining: Int) = V2Status(
        mode = mode,
        fanStep = fanStep,
        actual = 50,
        target = target,
        remainingSeconds = remaining,
        ledOn = true,
        muted = true,
        programStep = 0,
        shutdownReason = 0,
    )
}
