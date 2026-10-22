package com.knauerhase.lull.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knauerhase.lull.BedJetLink
import com.knauerhase.lull.BedJetState
import com.knauerhase.lull.Connection
import com.knauerhase.lull.bedjet.Mode
import com.knauerhase.lull.bedjet.adjustsTemperature
import com.knauerhase.lull.bedjet.maxMinutes
import com.knauerhase.lull.bedjet.temperatureRange
import kotlin.math.roundToInt

/** Direct control: the same layout as the iOS Remote tab. */
@Composable
fun RemoteScreen(link: BedJetLink, state: BedJetState) {
    val enabled = state.connection == Connection.READY
    val mode = state.mode
    val tint = mode.tint()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ClimateOrb(state)

        ModeGrid(
            selected = mode,
            enabled = enabled,
            onSelect = link::setMode,
        )

        if (state.connection == Connection.READY && mode != Mode.STANDBY) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TemperatureControl(state, enabled, link::setTarget)
                    FanControl(state, enabled, tint, link::setFan)
                    RuntimeControl(state, enabled, tint, link::setRuntime)
                }
            }
        }

        state.note?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = LullColors.Secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The glowing readout: what the BedJet is doing right now. */
@Composable
private fun ClimateOrb(state: BedJetState) {
    val mode = state.mode
    val isOn = mode != Mode.STANDBY
    val tint = mode.tint()
    val glow = if (isOn) 0.55f else 0.12f

    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .size(204.dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(tint.copy(alpha = glow), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2,
                    ),
                )
            }
            .border(width = if (isOn) 2.5.dp else 1.dp, color = tint.copy(alpha = 0.8f), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Eyebrow(modeLabel(mode).uppercase())
            Text(
                text = when {
                    state.connection != Connection.READY -> "--"
                    !isOn -> "Off"
                    !mode.adjustsTemperature(state.isV2()) -> "${state.fanPercent()}%"
                    else -> "${fahrenheit(state.targetHalfDegrees)}°"
                },
                fontSize = 60.sp,
                fontWeight = FontWeight.Thin,
            )
            Text(
                text = when {
                    state.connection != Connection.READY -> connectionText(state)
                    isOn -> "Fan ${state.fanPercent()}% · Air ${fahrenheit(state.actualHalfDegrees)}°"
                    else -> "Room ${fahrenheit(state.actualHalfDegrees)}°"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = LullColors.Secondary,
            )
        }
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LullColors.Secondary)
}

@Composable
private fun ModeGrid(selected: Mode, enabled: Boolean, onSelect: (Mode) -> Unit) {
    // Same order and names as the iOS Remote grid. Off is the last tile.
    val modes = listOf(Mode.COOL, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO, Mode.DRY, Mode.STANDBY)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        modes.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { mode ->
                    ModeTile(
                        label = modeLabel(mode),
                        tint = mode.tint(),
                        selected = mode == selected,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(mode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeTile(
    label: String,
    tint: Color,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) tint else LullColors.Raised)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.Black else if (enabled) tint else LullColors.Tertiary,
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(LullColors.Surface)
            .border(1.dp, LullColors.Hairline, RoundedCornerShape(28.dp))
            .padding(18.dp),
    ) {
        content()
    }
}

@Composable
private fun TemperatureControl(state: BedJetState, enabled: Boolean, onChange: (Int) -> Unit) {
    val range = temperatureRange(state.mode, state.isV2())
    if (range.first == range.last) {
        ReadoutRow("Temperature", "${fahrenheit(range.first)}°")
    } else {
        val target = state.targetHalfDegrees.coerceIn(range).toFloat()
        var local by remember { mutableFloatStateOf(target) }
        LaunchedEffect(target) { local = target }
        LabeledSlider(
            title = "Temperature",
            valueText = "${fahrenheit(local.roundToInt())}°",
            value = local,
            range = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            enabled = enabled,
            tint = state.mode.tint(),
            onValueChange = { local = it },
            onDone = { onChange(local.roundToInt()) },
        )
    }
}

@Composable
private fun FanControl(state: BedJetState, enabled: Boolean, tint: Color, onChange: (Int) -> Unit) {
    val percent = (state.fanPercent()).toFloat()
    var local by remember { mutableFloatStateOf(percent) }
    LaunchedEffect(percent) { local = percent }
    LabeledSlider(
        title = "Fan",
        valueText = "${(local / 5).roundToInt() * 5}%",
        value = local,
        range = 5f..100f,
        steps = 18,
        enabled = enabled,
        tint = tint,
        onValueChange = { local = it },
        onDone = { onChange((local / 5).roundToInt() - 1) },
    )
}

@Composable
private fun RuntimeControl(state: BedJetState, enabled: Boolean, tint: Color, onChange: (Int) -> Unit) {
    val longest = state.mode.maxMinutes
    val step = when {
        longest <= 15 -> 1
        longest <= 240 -> 5
        else -> 15
    }
    val minutes = ((state.remainingSeconds + 59) / 60).coerceIn(step, longest).toFloat()
    var local by remember { mutableFloatStateOf(minutes) }
    LaunchedEffect(minutes) { local = minutes }
    LabeledSlider(
        title = "Turns off in",
        valueText = formatDuration(local.roundToInt()),
        value = local,
        range = step.toFloat()..longest.toFloat(),
        steps = (longest - step) / step - 1,
        enabled = enabled,
        tint = tint,
        onValueChange = { local = it },
        onDone = { onChange(local.roundToInt()) },
    )
}

@Composable
private fun ReadoutRow(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = LullColors.Secondary)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean,
    tint: Color,
    onValueChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = LullColors.Secondary)
            Spacer(Modifier.weight(1f))
            Text(valueText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
                inactiveTrackColor = LullColors.Raised,
            ),
        )
    }
}

private fun modeLabel(mode: Mode): String = when (mode) {
    Mode.COOL -> "Cool"
    Mode.HEAT -> "Heat"
    Mode.EXTENDED_HEAT -> "Gentle heat"
    Mode.TURBO -> "Turbo"
    Mode.DRY -> "Dry"
    Mode.STANDBY -> "Off"
    Mode.WAIT -> "Wait"
}

private fun connectionText(state: BedJetState): String = when (state.connection) {
    Connection.IDLE -> "Not connected"
    Connection.SCANNING -> "Looking for your BedJet"
    Connection.CONNECTING -> "Connecting"
    Connection.FAILED -> "Couldn't connect"
    Connection.READY -> ""
}

private fun BedJetState.fanPercent(): Int = (fanStep + 1) * 5

private fun BedJetState.isV2(): Boolean = generation == "BedJet V2"

/** Half degrees Celsius to whole degrees Fahrenheit, as the iOS app shows them. */
private fun fahrenheit(halfDegrees: Int): Int = ((halfDegrees / 2.0) * 9 / 5 + 32).roundToInt()

private fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours hr"
        else -> "$hours hr $rest min"
    }
}
