package com.fwr.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom lightweight Material-style vector icons so we do not pull in the full
 * `material-icons-extended` dependency when release minification is disabled.
 */
object RwfIcons {

    val Reply: ImageVector by lazy {
        ImageVector.Builder(
            name = "RwfReply",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 9f)
                verticalLineTo(5f)
                lineTo(3f, 12f)
                lineTo(10f, 19f)
                verticalLineTo(14.9f)
                curveTo(15f, 14.9f, 18.5f, 16.5f, 21f, 20f)
                curveTo(20f, 15f, 17f, 10f, 10f, 9f)
                close()
            }
        }.build()
    }

    val Crosspost: ImageVector by lazy {
        ImageVector.Builder(
            name = "RwfCrosspost",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10.59f, 9.17f)
                lineTo(5.41f, 4f)
                lineTo(4f, 5.41f)
                lineTo(9.17f, 10.58f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(14.5f, 4f)
                lineTo(16.54f, 6.04f)
                lineTo(4f, 18.59f)
                lineTo(5.41f, 20f)
                lineTo(17.96f, 7.46f)
                lineTo(20f, 9.5f)
                verticalLineTo(4f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(14.83f, 13.41f)
                lineTo(13.42f, 14.82f)
                lineTo(16.55f, 17.95f)
                lineTo(14.5f, 20f)
                horizontalLineTo(20f)
                verticalLineTo(14.5f)
                lineTo(17.96f, 16.54f)
                close()
            }
        }.build()
    }

    val Multireddit: ImageVector by lazy {
        ImageVector.Builder(
            name = "RwfMultireddit",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(11.99f, 18.54f)
                lineTo(4.62f, 12.81f)
                lineTo(3f, 14.07f)
                lineTo(12f, 21.07f)
                lineTo(21f, 14.07f)
                lineTo(19.37f, 12.8f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 16f)
                lineTo(19.36f, 10.27f)
                lineTo(21f, 9f)
                lineTo(12f, 2f)
                lineTo(3f, 9f)
                lineTo(4.63f, 10.27f)
                close()
            }
        }.build()
    }
}
