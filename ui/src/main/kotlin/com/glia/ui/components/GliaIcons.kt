package com.glia.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Ícones próprios: traço orgânico, coerente com o solarpunk. */
object GliaIcons {

    private fun icon(name: String, pathData: String): ImageVector {
        val nodes = PathParser().parsePathString(pathData).toNodes()
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = nodes,
            fill = SolidColor(Color.Black),
        ).build()
    }

    val Mic: ImageVector by lazy {
        icon(
            "Mic",
            "M12,14a3,3 0 0,0 3,-3V5a3,3 0 0,0 -6,0v6a3,3 0 0,0 3,3z" +
                "M17,11a5,5 0 0,1 -10,0H5a7,7 0 0,0 6,6.92V21h2v-3.08A7,7 0 0,0 19,11z",
        )
    }

    val Chat: ImageVector by lazy {
        icon(
            "Chat",
            "M4,3h16a2,2 0 0,1 2,2v10a2,2 0 0,1 -2,2H9l-5,4V5a2,2 0 0,1 2,-2z",
        )
    }

    val Orb: ImageVector by lazy {
        icon(
            "Orb",
            "M12,2a9,9 0 1,0 0,18a9,9 0 1,0 0,-18zM12,20v3M12,22l3,-3M12,22l-3,-2",
        )
    }

    val Sliders: ImageVector by lazy {
        icon(
            "Sliders",
            "M4,6h9v2H4zM17,6h3v2h-3zM4,11h4v2H4zM11,11h9v2h-9zM4,16h12v2H4zM19,16h1v2h-1z",
        )
    }

    val Send: ImageVector by lazy {
        icon("Send", "M2,21l20,-9L2,3v7l14,2L2,14z")
    }

    val Back: ImageVector by lazy {
        icon("Back", "M20,11H7.8l5.6,-5.6L12,4l-8,8l8,8l1.4,-1.4L7.8,13H20z")
    }

    val Close: ImageVector by lazy {
        icon(
            "Close",
            "M18.3,5.7L12,12l6.3,6.3l-1.4,1.4L10.6,13.4L4.3,19.7L2.9,18.3L9.2,12L2.9,5.7L4.3,4.3l6.3,6.3l6.3,-6.3z",
        )
    }

    val Stop: ImageVector by lazy { icon("Stop", "M6,6h12v12H6z") }

    val Leaf: ImageVector by lazy {
        icon(
            "Leaf",
            "M20,3C12,3 4,7 4,15c0,3 2,5 5,5C18,20 20,12 20,3zM6,19c1,-6 5,-9 9,-11",
        )
    }

    val Check: ImageVector by lazy {
        icon("Check", "M9,16.2L4.8,12l-1.4,1.4L9,19L21,7l-1.4,-1.4z")
    }

    val Shield: ImageVector by lazy {
        icon("Shield", "M12,2l8,3v7c0,5 -4,8 -8,10c-4,-2 -8,-5 -8,-10V5z")
    }

    val Clock: ImageVector by lazy {
        icon("Clock", "M12,2a10,10 0 1,0 0,20a10,10 0 1,0 0,-20zM11,7h2v6l5,3l-1,1.7l-6,-3.7z")
    }

    val Sparkle: ImageVector by lazy {
        icon(
            "Sparkle",
            "M12,2l2.2,6.2L20,10l-5.8,1.8L12,18l-2.2,-6.2L4,10l5.8,-1.8z",
        )
    }
}
