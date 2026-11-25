package com.glia.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.glia.ui.theme.GliaTheme

/**
 * Fundo vivo: gradiente musgo, manchas de luz, raios de sol e trepadeiras.
 * Tudo em Canvas — nenhum bitmap, nenhum blur de tela cheia.
 */
@Composable
fun OrganicBackdrop(modifier: Modifier = Modifier) {
    val design = GliaTheme.design
    val palette = design.palette

    val drift = if (design.animations) {
        val transition = rememberInfiniteTransition(label = "backdrop")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(26_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "drift",
        )
        value
    } else {
        0.35f
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(palette.backgroundTop, palette.backgroundBottom),
            ),
        )

        // Sol alto à direita.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.sun.copy(alpha = 0.40f),
                    palette.sun.copy(alpha = 0.10f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.86f, size.height * 0.06f),
                radius = size.minDimension * 0.72f,
            ),
            radius = size.minDimension * 0.72f,
            center = Offset(size.width * 0.86f, size.height * 0.06f),
        )

        // Mancha de folha.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.leaf.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(size.width * (0.12f + 0.06f * drift), size.height * 0.30f),
                radius = size.minDimension * 0.55f,
            ),
            radius = size.minDimension * 0.55f,
            center = Offset(size.width * (0.12f + 0.06f * drift), size.height * 0.30f),
        )

        // Água embaixo.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.teal.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width * 0.72f, size.height * (0.88f - 0.04f * drift)),
                radius = size.minDimension * 0.6f,
            ),
            radius = size.minDimension * 0.6f,
            center = Offset(size.width * 0.72f, size.height * (0.88f - 0.04f * drift)),
        )

        // Raios de sol discretos.
        val rayAlpha = 0.10f + 0.04f * drift
        for (i in 0..4) {
            val start = Offset(size.width * 0.78f, -size.height * 0.05f)
            val end = Offset(size.width * (0.55f + i * 0.12f), size.height * (0.55f + i * 0.06f))
            drawLine(
                brush = Brush.linearGradient(
                    listOf(palette.sun.copy(alpha = rayAlpha), Color.Transparent),
                    start = start,
                    end = end,
                ),
                start = start,
                end = end,
                strokeWidth = 26f + i * 12f,
                cap = StrokeCap.Round,
            )
        }

        // Trepadeira / ramificação de glia, no canto inferior esquerdo.
        val vineColor = palette.moss.copy(alpha = if (design.dark) 0.28f else 0.22f)
        val vine = Path().apply {
            moveTo(0f, size.height)
            cubicTo(
                size.width * 0.10f, size.height * 0.82f,
                size.width * 0.02f, size.height * 0.62f,
                size.width * 0.16f, size.height * 0.48f,
            )
            cubicTo(
                size.width * 0.22f, size.height * 0.41f,
                size.width * 0.19f, size.height * 0.33f,
                size.width * 0.26f, size.height * 0.25f,
            )
        }
        drawPath(vine, color = vineColor, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
        for (i in 1..4) {
            val origin = Offset(size.width * (0.08f + i * 0.035f), size.height * (0.80f - i * 0.12f))
            val branch = Path().apply {
                moveTo(origin.x, origin.y)
                cubicTo(
                    origin.x + 40f, origin.y - 26f,
                    origin.x + 66f, origin.y - 46f,
                    origin.x + 96f, origin.y - 40f,
                )
            }
            drawPath(branch, color = vineColor, style = Stroke(width = 1.6f, cap = StrokeCap.Round))
            drawCircle(
                color = palette.leaf.copy(alpha = 0.30f),
                radius = 5f + (i % 2) * 2f,
                center = Offset(origin.x + 96f, origin.y - 40f),
            )
        }
    }
}
