package com.vaani.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vaani.app.ui.theme.VaaniIndigo
import com.vaani.app.ui.theme.VaaniSaffron

/** A circle with a five-bar voice waveform. */
@Composable
fun VaaniLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        drawCircle(Brush.linearGradient(listOf(VaaniIndigo, VaaniSaffron)))
        val heights = floatArrayOf(0.22f, 0.42f, 0.58f, 0.42f, 0.22f)
        val barWidth = this.size.width * 0.085f
        val gap = barWidth * 0.9f
        val total = heights.size * barWidth + (heights.size - 1) * gap
        var x = (this.size.width - total) / 2
        heights.forEach { h ->
            val barHeight = this.size.height * h
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(x, (this.size.height - barHeight) / 2),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
            x += barWidth + gap
        }
    }
}
