package com.knauerhase.lull

import com.knauerhase.lull.bedjet.Mode
import com.knauerhase.lull.bedjet.maxMinutes
import com.knauerhase.lull.bedjet.temperatureRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A BedJet that exists only in memory, for trying the screens without hardware. It doesn't heat or cool anything. */
class DemoBedJetLink : BedJetLink {
    private val _state = MutableStateFlow(
        BedJetState(
            connection = Connection.READY,
            generation = "Demo",
            actualHalfDegrees = 66,
            targetHalfDegrees = 70,
            fanStep = 9,
            note = "Demo mode. Nothing is sent to a real BedJet.",
        ),
    )
    override val state: StateFlow<BedJetState> = _state.asStateFlow()

    override fun connect() {}

    override fun disconnect() {}

    override fun setMode(mode: Mode) {
        _state.update { current ->
            val next = if (mode == Mode.STANDBY || mode == current.mode) Mode.STANDBY else mode
            current.copy(
                mode = next,
                targetHalfDegrees = current.targetHalfDegrees.coerceIn(temperatureRange(next, v2 = false)),
                fanStep = if (next == Mode.TURBO) 19 else current.fanStep,
                remainingSeconds = if (next == Mode.STANDBY) 0 else minOf(next.maxMinutes, MAX_SCHEDULE_MINUTES) * 60,
                programStep = 0,
            )
        }
    }

    override fun setTarget(halfDegrees: Int) {
        _state.update { it.copy(targetHalfDegrees = halfDegrees.coerceIn(temperatureRange(it.mode, v2 = false))) }
    }

    override fun setFan(step: Int) {
        _state.update { it.copy(fanStep = step.coerceIn(0, 19)) }
    }

    override fun setRuntime(minutes: Int) {
        _state.update { it.copy(remainingSeconds = minutes.coerceIn(1, it.mode.maxMinutes) * 60) }
    }

    override fun startSchedule(schedule: Schedule, slot: Int) {
        val first = schedule.phases.firstOrNull() ?: return
        _state.update {
            it.copy(
                mode = first.mode,
                targetHalfDegrees = first.temperature,
                fanStep = first.fanStep,
                remainingSeconds = first.minutes * 60,
                programStep = 1,
                note = "Demo: \"${schedule.name}\" is shown as running. Nothing is sent to a BedJet.",
            )
        }
    }
}
