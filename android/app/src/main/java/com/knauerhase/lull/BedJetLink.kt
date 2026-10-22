package com.knauerhase.lull

import com.knauerhase.lull.bedjet.Mode
import kotlinx.coroutines.flow.StateFlow

enum class Connection { IDLE, SCANNING, CONNECTING, READY, FAILED }

/** What the screen shows. Temperatures are in half degrees Celsius, as the BedJet reports them. */
data class BedJetState(
    val connection: Connection = Connection.IDLE,
    val generation: String? = null,
    val mode: Mode = Mode.STANDBY,
    val actualHalfDegrees: Int = 0,
    val targetHalfDegrees: Int = 0,
    val fanStep: Int = 0,
    val remainingSeconds: Int = 0,
    val note: String? = null,
)

/** One BedJet, real or simulated. The screen only talks to this interface. */
interface BedJetLink {
    val state: StateFlow<BedJetState>

    fun connect()

    fun disconnect()

    /** Selecting the current mode turns the air off, and [Mode.STANDBY] turns it off. */
    fun setMode(mode: Mode)

    fun setTarget(halfDegrees: Int)

    /** 0..19, where the fan runs at (step + 1) * 5 percent. */
    fun setFan(step: Int)

    fun setRuntime(minutes: Int)
}
