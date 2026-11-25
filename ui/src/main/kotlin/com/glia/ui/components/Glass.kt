package com.glia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glia.ui.theme.GliaTheme

/**
 * Vidro líquido sem custo de blur de tela cheia:
 * gradiente translúcido + borda especular + brilho radial no canto.
 * Em GPUs modestas (Mali-G57), isso mantém 60 fps com muitas superfícies.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color? = null,
    elevationTint: Color? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val design = GliaTheme.design
    val palette = design.palette
    val base = tint ?: palette.glassTint
    val highlight = palette.glassHighlight
    val borderColor = palette.glassBorder
    val sheen = elevationTint ?: palette.leaf

    val interaction = remember { MutableInteractionSource() }
    val clickable = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    listOf(
                        highlight.copy(alpha = if (design.dark) 0.10f else 0.55f),
                        base,
                        base.copy(alpha = if (design.dark) 0.92f else 0.75f),
                    ),
                ),
                shape = shape,
            )
            .drawBehind {
                // brilho especular suave no topo.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            highlight.copy(alpha = if (design.dark) 0.10f else 0.45f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.25f, 0f),
                        radius = size.maxDimension * 0.85f,
                    ),
                )
                // profundidade na base.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            sheen.copy(alpha = if (design.dark) 0.10f else 0.06f),
                        ),
                        startY = size.height * 0.45f,
                        endY = size.height,
                    ),
                )
            }
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        borderColor,
                        borderColor.copy(alpha = 0.18f),
                        borderColor.copy(alpha = 0.5f),
                    ),
                ),
                shape = shape,
            )
            .then(clickable)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun GlassPill(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tint: Color? = null,
) {
    GlassSurface(
        modifier = modifier,
        shape = CircleShape,
        tint = tint,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
        onClick = onClick,
    ) {
        androidx.compose.material3.Text(
            text = text,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            color = GliaTheme.palette.textSecondary,
        )
    }
}

/** Pequeno indicador luminoso usado em estados. */
@Composable
fun GlowDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.15f)),
                ),
            ),
    )
}

/** Divisor de vidro sutil. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        GliaTheme.palette.textSecondary.copy(alpha = 0.18f),
                        Color.Transparent,
                    ),
                ),
            )
    )
}

