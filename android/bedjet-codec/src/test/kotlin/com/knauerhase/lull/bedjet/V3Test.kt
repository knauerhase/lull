package com.knauerhase.lull.bedjet

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class V3Test {
    @Test
    fun runtimeSplitsHoursAndMinutes() {
        assertContentEquals(byteArrayOf(0x02, 2, 30), V3.setRuntime(150))
    }

    @Test
    fun nameIsPaddedAndTrimmed() {
        val command = V3.nameProgram(1, "A very long schedule name")
        assertEquals(19, command.size)
        assertContentEquals(byteArrayOf(0x40, 0x05, 0x10), command.copyOf(3))
        assertEquals("A very long sch", command.copyOfRange(3, 19).takeWhile { it != 0.toByte() }.toByteArray().decodeToString())
    }

    @Test
    fun longProgramsSplitIntoFragments() {
        val steps = (0 until 19).map { V3Step(Mode.COOL, fanStep = it, temperature = 40, hours = 0, minutes = 30) }
        val commands = V3.writeProgram(2, steps)
        assertEquals(listOf(15, 16, 17), commands.map { it[1].toInt() })
        assertEquals(19, commands[0][2].toInt())
        assertEquals(43, commands[2].size)
    }
}
