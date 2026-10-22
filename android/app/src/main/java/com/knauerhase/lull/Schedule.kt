package com.knauerhase.lull

import android.content.Context
import com.knauerhase.lull.bedjet.Mode
import com.knauerhase.lull.bedjet.heatLongestMinutes
import com.knauerhase.lull.bedjet.maxMinutes
import com.knauerhase.lull.bedjet.temperatureRange
import org.json.JSONArray
import org.json.JSONObject

/** The unit holds one schedule per program slot on a BedJet 3; the app keeps these three on the phone. */
const val SAVED_SLOTS = 3

/** A night is at most ten hours, as in the iOS app. */
const val MAX_SCHEDULE_MINUTES = 10 * 60

/** A BedJet V2 holds one sequence of at most this many phases. */
const val MAX_PHASES = 31

private val V2_MODES = setOf(Mode.HEAT, Mode.TURBO, Mode.COOL, Mode.WAIT)

/** One stretch of a schedule. Temperature is in half degrees Celsius and is ignored when the mode doesn't take one. */
data class Phase(val mode: Mode, val fanStep: Int, val temperature: Int, val minutes: Int) {
    /** Longest this phase may run, given the limits for its mode, temperature and fan. */
    val limitMinutes: Int
        get() = when (mode) {
            Mode.HEAT -> minOf(Mode.HEAT.maxMinutes, heatLongestMinutes(temperature, fanStep))
            else -> minOf(mode.maxMinutes, MAX_SCHEDULE_MINUTES)
        }
}

data class Schedule(val name: String, val phases: List<Phase>) {
    val totalMinutes: Int get() = phases.sumOf { it.minutes }

    /** Why this schedule can't run on the unit, or null when it can. */
    fun problemFor(v2: Boolean): String? = when {
        phases.isEmpty() -> "Add at least one phase first."
        totalMinutes > MAX_SCHEDULE_MINUTES -> "A schedule can run up to 10 hours."
        phases.any { it.minutes < 1 || it.minutes > it.limitMinutes } -> "A phase is longer than its mode allows."
        v2 && phases.any { it.mode !in V2_MODES } ->
            "A BedJet V2 doesn't have Gentle heat or Dry. Change those phases to Heat or Cool."
        else -> null
    }
}

/** A sensible starting phase for [mode], in the middle of its temperature range. */
fun defaultPhase(mode: Mode): Phase {
    val range = temperatureRange(mode, v2 = false)
    val minutes = when (mode) {
        Mode.TURBO -> 10
        Mode.WAIT -> 15
        else -> 60
    }
    return Phase(mode, fanStep = 9, temperature = (range.first + range.last) / 2, minutes = minutes)
}

/** The three saved schedules, kept as JSON in this app's private preferences. */
class ScheduleStore(context: Context) {
    private val prefs = context.getSharedPreferences("andlull", Context.MODE_PRIVATE)

    fun load(): List<Schedule?> {
        val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        return List(SAVED_SLOTS) { index -> array.optJSONObject(index)?.let(::decode) }
    }

    fun save(slots: List<Schedule?>) {
        val array = JSONArray()
        slots.forEach { schedule -> array.put(schedule?.let(::encode) ?: JSONObject.NULL) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun encode(schedule: Schedule): JSONObject {
        val phases = JSONArray()
        schedule.phases.forEach { phase ->
            phases.put(
                JSONObject()
                    .put("mode", phase.mode.name)
                    .put("fan", phase.fanStep)
                    .put("temperature", phase.temperature)
                    .put("minutes", phase.minutes),
            )
        }
        return JSONObject().put("name", schedule.name).put("phases", phases)
    }

    private fun decode(json: JSONObject): Schedule? = runCatching {
        val phases = json.getJSONArray("phases")
        Schedule(
            name = json.getString("name"),
            phases = List(phases.length()) { index ->
                val phase = phases.getJSONObject(index)
                Phase(
                    mode = Mode.valueOf(phase.getString("mode")),
                    fanStep = phase.getInt("fan"),
                    temperature = phase.getInt("temperature"),
                    minutes = phase.getInt("minutes"),
                )
            },
        )
    }.getOrNull()

    private companion object {
        const val KEY = "schedules"
    }
}
