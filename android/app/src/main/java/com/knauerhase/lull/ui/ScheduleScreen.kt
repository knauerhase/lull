package com.knauerhase.lull.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knauerhase.lull.BedJetState
import com.knauerhase.lull.Connection
import com.knauerhase.lull.MAX_SCHEDULE_MINUTES
import com.knauerhase.lull.Phase
import com.knauerhase.lull.Schedule
import com.knauerhase.lull.maxPhases
import com.knauerhase.lull.bedjet.Mode
import com.knauerhase.lull.bedjet.temperatureRange
import com.knauerhase.lull.defaultPhase
import kotlin.math.roundToInt

/** Saved schedules, with an editor for building one. Starting a schedule writes it to the BedJet and runs it. */
@Composable
fun ScheduleScreen(
    state: BedJetState,
    slots: List<Schedule?>,
    onSave: (Int, Schedule?) -> Unit,
    onStart: (Schedule, Int) -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    val slot = editing
    val deleteSlot: () -> Unit = {
        if (slot != null) onSave(slot, null)
        editing = null
    }
    if (slot == null) {
        SlotList(state, slots, onEdit = { editing = it }, onStart = onStart)
    } else {
        ScheduleEditor(
            v2 = state.generation == "BedJet V2",
            initial = slots[slot],
            onCancel = { editing = null },
            onSave = { schedule ->
                onSave(slot, schedule)
                editing = null
            },
            onDelete = if (slots[slot] == null) null else deleteSlot,
        )
    }
}

@Composable
private fun SlotList(
    state: BedJetState,
    slots: List<Schedule?>,
    onEdit: (Int) -> Unit,
    onStart: (Schedule, Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Eyebrow("Saved schedules")
        slots.forEachIndexed { index, schedule ->
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (schedule == null) {
                        Text("Slot ${index + 1} is empty", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = { onEdit(index) }) { Text("Build a schedule") }
                    } else {
                        Text(schedule.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        NightCurve(schedule.phases, modifier = Modifier.fillMaxWidth().height(90.dp), lineWidth = 2.dp)
                        Text(
                            text = "${schedule.phases.size} phases · ${formatDuration(schedule.totalMinutes)}",
                            fontSize = 14.sp,
                            color = LullColors.Secondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { onEdit(index) }) { Text("Edit") }
                            Button(
                                onClick = { onStart(schedule, index) },
                                enabled = state.connection == Connection.READY,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            ) { Text("Start") }
                        }
                    }
                }
            }
        }
        state.note?.let {
            Text(text = it, fontSize = 13.sp, color = LullColors.Secondary)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ScheduleEditor(
    v2: Boolean,
    initial: Schedule?,
    onCancel: () -> Unit,
    onSave: (Schedule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    val phases = remember { mutableStateListOf<Phase>().apply { addAll(initial?.phases ?: listOf(defaultPhase(Mode.HEAT))) } }
    val total = phases.sumOf { it.minutes }
    val draft = Schedule(name.trim().ifEmpty { "Schedule" }, phases.toList())
    val problem = draft.problemFor(v2)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NightCurve(phases.toList(), modifier = Modifier.fillMaxWidth().height(150.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(15) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        phases.forEachIndexed { index, phase ->
            PhaseCard(
                v2 = v2,
                number = index + 1,
                phase = phase,
                budgetLeft = MAX_SCHEDULE_MINUTES - (total - phase.minutes),
                onChange = { phases[index] = it },
                onRemove = { phases.removeAt(index) },
            )
        }

        TimeBudget(phases.toList())

        OutlinedButton(
            onClick = {
                // A new phase takes at most what's left of the ten hours.
                val fresh = defaultPhase(Mode.COOL)
                phases.add(fresh.copy(minutes = minOf(fresh.minutes, MAX_SCHEDULE_MINUTES - total)))
            },
            enabled = phases.size < maxPhases(v2) && total < MAX_SCHEDULE_MINUTES,
        ) { Text("Add phase") }

        problem?.let { Text(text = it, fontSize = 13.sp, color = LullColors.Hot) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = { onSave(draft) },
                enabled = problem == null,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) { Text("Save") }
        }

        onDelete?.let {
            TextButton(onClick = it) { Text("Delete schedule", color = LullColors.Hot) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PhaseCard(
    v2: Boolean,
    number: Int,
    phase: Phase,
    budgetLeft: Int,
    onChange: (Phase) -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Phase $number")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("Remove", color = LullColors.Hot) }
            }

            val choices = listOf(Mode.COOL, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO, Mode.DRY, Mode.WAIT)
            choices.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { mode ->
                        ModeTile(
                            label = modeLabel(mode),
                            tint = mode.tint(),
                            selected = mode == phase.mode,
                            enabled = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                // Keep the fan and as much of the time as the new mode allows.
                                val fresh = defaultPhase(mode).copy(fanStep = phase.fanStep)
                                onChange(fresh.copy(minutes = minOf(phase.minutes, fresh.limitMinutes).coerceAtLeast(1)))
                            },
                        )
                    }
                }
            }

            val range = temperatureRange(phase.mode, v2)
            if (phase.mode != Mode.WAIT) {
                if (range.first == range.last) {
                    ReadoutRow("Temperature", "${fahrenheit(range.first)}°")
                } else {
                    LabeledSlider(
                        title = "Temperature",
                        valueText = "${fahrenheit(phase.temperature)}°",
                        value = phase.temperature.toFloat(),
                        range = range.first.toFloat()..range.last.toFloat(),
                        steps = range.last - range.first - 1,
                        enabled = true,
                        tint = phase.mode.tint(),
                        onValueChange = { onChange(phase.copy(temperature = it.roundToInt())) },
                        onDone = {},
                    )
                }

                LabeledSlider(
                    title = "Fan",
                    valueText = "${(phase.fanStep + 1) * 5}%",
                    value = ((phase.fanStep + 1) * 5).toFloat(),
                    range = 5f..100f,
                    steps = 18,
                    enabled = true,
                    tint = phase.mode.tint(),
                    onValueChange = { onChange(phase.copy(fanStep = (it / 5).roundToInt() - 1)) },
                    onDone = {},
                )
            }

            // The range is the mode's own limit, so this slider doesn't move when another phase changes. Dragging
            // stops at what's left of the ten hours.
            val cap = minOf(phase.limitMinutes, budgetLeft)
            val step = if (phase.limitMinutes <= 15) 1 else 5
            LabeledSlider(
                title = "Duration",
                valueText = formatDuration(phase.minutes),
                value = phase.minutes.coerceIn(step, phase.limitMinutes).toFloat(),
                range = step.toFloat()..phase.limitMinutes.toFloat(),
                steps = (phase.limitMinutes - step) / step - 1,
                enabled = true,
                tint = phase.mode.tint(),
                onValueChange = {
                    val next = ((it / step).roundToInt() * step).coerceAtMost(cap).coerceAtLeast(1)
                    onChange(phase.copy(minutes = next))
                },
                onDone = {},
            )
        }
    }
}

/** How much of the ten hours the phases use, drawn as a bar with a full mark at the end. */
@Composable
private fun TimeBudget(phases: List<Phase>) {
    val total = phases.sumOf { it.minutes }
    val left = MAX_SCHEDULE_MINUTES - total
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            drawRoundRect(LullColors.Raised, cornerRadius = CornerRadius(5.dp.toPx()))
            var x = 0f
            phases.forEach { phase ->
                val width = size.width * phase.minutes / MAX_SCHEDULE_MINUTES
                drawRect(phase.mode.tint(), topLeft = Offset(x, 0f), size = Size(width, size.height))
                x += width
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (left <= 0) "Schedule ${formatDuration(total)}, full" else "Schedule ${formatDuration(total)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (left <= 0) LullColors.Warm else LullColors.Secondary,
            )
            Spacer(Modifier.weight(1f))
            if (left > 0) {
                Text("${formatDuration(left)} left of 10 hours", fontSize = 14.sp, color = LullColors.Secondary)
            }
        }
    }
}
