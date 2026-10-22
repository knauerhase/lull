package com.knauerhase.lull.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.knauerhase.lull.Phase
import com.knauerhase.lull.bedjet.Mode

/**
 * The night drawn as one line: above the midline is warming, below it is cooling. Port of `NightCurve.swift`.
 * [progress] is 0..1 through a running schedule; the part still ahead is drawn dimmer.
 */
@Composable
fun NightCurve(
    phases: List<Phase>,
    modifier: Modifier = Modifier.fillMaxWidth().height(150.dp),
    progress: Float? = null,
    lineWidth: Dp = 3.dp,
) {
    Canvas(modifier = modifier) {
        val inset = 8.dp.toPx()
        val width = size.width - 2 * inset
        val height = size.height
        val total = maxOf(phases.sumOf { it.minutes }, 1).toDouble()
        val mid = height / 2
        val amplitude = height * 0.42f

        fun pointAt(fraction: Float): Offset {
            val level = levelAt(fraction * total, phases)
            return Offset(inset + width * fraction, mid - (level * amplitude).toFloat())
        }

        // Phase boundaries as small ticks, and a dashed midline.
        var elapsed = 0.0
        for (phase in phases) {
            val x = inset + width * (elapsed / total).toFloat()
            if (elapsed > 0) {
                drawRect(Color.White.copy(alpha = 0.18f), topLeft = Offset(x, mid - 5.dp.toPx()), size = Size(1.dp.toPx(), 10.dp.toPx()))
            }
            elapsed += phase.minutes
        }
        drawLine(
            color = Color.White.copy(alpha = 0.12f),
            start = Offset(inset, mid),
            end = Offset(inset + width, mid),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx())),
        )

        val samples = maxOf((width / 2.dp.toPx()).toInt(), 2)
        val line = Path().apply {
            val first = pointAt(0f)
            moveTo(first.x, first.y)
            for (index in 1..samples) {
                val point = pointAt(index.toFloat() / samples)
                lineTo(point.x, point.y)
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(inset + width, mid)
            lineTo(inset, mid)
            close()
        }

        val top = mid - amplitude
        val bottom = mid + amplitude
        val fill = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to LullColors.Hot.copy(alpha = 0.45f),
                0.5f to LullColors.Warm.copy(alpha = 0f),
                0.5f to LullColors.Cool.copy(alpha = 0f),
                1f to LullColors.DeepCool.copy(alpha = 0.45f),
            ),
            startY = top,
            endY = bottom,
        )
        val stroke = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to LullColors.Hot,
                0.3f to LullColors.Warm,
                0.5f to Color(0xFFD9D9D9),
                0.7f to LullColors.Cool,
                1f to LullColors.DeepCool,
            ),
            startY = top,
            endY = bottom,
        )
        val lineStyle = Stroke(width = lineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        val glowStyle = Stroke(width = lineWidth.toPx() * 4, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun draw(alpha: Float) {
            drawPath(area, fill, alpha = alpha)
            // Blur isn't available on a plain canvas, so the glow is a wider, fainter stroke underneath.
            drawPath(line, stroke, alpha = 0.2f * alpha, style = glowStyle)
            drawPath(line, stroke, alpha = alpha, style = lineStyle)
        }

        if (progress == null) {
            draw(1f)
            return@Canvas
        }
        // The part of the night still ahead is drawn dimmer than what has already happened.
        draw(0.3f)
        clipRect(left = 0f, top = 0f, right = size.width * progress, bottom = height) { draw(1f) }

        val now = pointAt(progress)
        drawCircle(Color.White.copy(alpha = 0.3f), radius = 9.dp.toPx(), center = now)
        drawCircle(Color.White, radius = 6.dp.toPx(), center = now)
    }
}

/** Heat and turbo sit above the midline, cooling below it, scaled by how hot or how strong the air is. */
val Phase.level: Double
    get() = when (mode) {
        Mode.COOL -> -(0.25 + 0.75 * ((fanStep + 1) * 5) / 100.0)
        Mode.DRY -> -0.15
        Mode.STANDBY, Mode.WAIT -> 0.0
        Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO -> 0.2 + 0.8 * (temperature - 38) / 48.0
    }

/** Phase levels joined by short eased ramps, so the line reads as a curve rather than steps. */
private fun levelAt(minute: Double, phases: List<Phase>): Double {
    var start = 0.0
    phases.forEachIndexed { index, phase ->
        val end = start + phase.minutes
        if (minute <= end || index == phases.size - 1) {
            val previous = phases.getOrNull(index - 1)
            if (previous != null) {
                val ramp = ramp(previous, phase)
                if (ramp != null && minute - start < ramp) {
                    return blend(previous.level, phase.level, 0.5 + (minute - start) / (2 * ramp))
                }
            }
            val next = phases.getOrNull(index + 1)
            if (next != null) {
                val ramp = ramp(phase, next)
                if (ramp != null && end - minute < ramp) {
                    return blend(phase.level, next.level, 0.5 - (end - minute) / (2 * ramp))
                }
            }
            return phase.level
        }
        start = end
    }
    return 0.0
}

private fun ramp(a: Phase, b: Phase): Double? {
    val width = minOf(40.0, a.minutes / 2.0, b.minutes / 2.0)
    return if (width > 0) width else null
}

private fun blend(from: Double, to: Double, t: Double): Double {
    val eased = t * t * (3 - 2 * t)
    return from + (to - from) * eased
}
