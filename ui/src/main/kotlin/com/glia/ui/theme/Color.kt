package com.glia.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta solarpunk: folha, musgo, água, sol e céu.
 * Nada de preto puro nem neon agressivo.
 */
data class GliaPalette(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val leaf: Color,
    val moss: Color,
    val teal: Color,
    val sun: Color,
    val sky: Color,
    val cream: Color,
    val brass: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val glassTint: Color,
    val glassHighlight: Color,
    val glassBorder: Color,
    val bubbleUser: Color,
    val bubbleGlia: Color,
    val danger: Color,
)

val SolarpunkLight = GliaPalette(
    backgroundTop = Color(0xFFFBF7EC),
    backgroundBottom = Color(0xFFE9F2E1),
    leaf = Color(0xFF2F6B4F),
    moss = Color(0xFF4E7F57),
    teal = Color(0xFF2C8C7D),
    sun = Color(0xFFE9C46A),
    sky = Color(0xFFB4DCE8),
    cream = Color(0xFFFFFDF6),
    brass = Color(0xFFB08D57),
    textPrimary = Color(0xFF1D3527),
    textSecondary = Color(0xFF4C6355),
    glassTint = Color(0xB3FFFFFF),
    glassHighlight = Color(0xCCFFFFFF),
    glassBorder = Color(0x8CFFFFFF),
    bubbleUser = Color(0xFFDCEBD4),
    bubbleGlia = Color(0xB3FFFFFF),
    danger = Color(0xFF9A4A3C),
)

val SolarpunkDark = GliaPalette(
    backgroundTop = Color(0xFF0C1F18),
    backgroundBottom = Color(0xFF17352A),
    leaf = Color(0xFF9ED9A9),
    moss = Color(0xFF74B089),
    teal = Color(0xFF7FD6C8),
    sun = Color(0xFFEBCB7A),
    sky = Color(0xFF7FA9B8),
    cream = Color(0xFFF3F7EC),
    brass = Color(0xFFC9A46A),
    textPrimary = Color(0xFFEAF3E6),
    textSecondary = Color(0xFFA9C4B2),
    glassTint = Color(0x66142C22),
    glassHighlight = Color(0x33FFFFFF),
    glassBorder = Color(0x33D7F0DD),
    bubbleUser = Color(0xFF234A38),
    bubbleGlia = Color(0x591B3A2C),
    danger = Color(0xFFE0A090),
)
