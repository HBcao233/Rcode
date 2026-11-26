/*
Source: https://fonts.google.com/icons?icon.size=24&icon.color=%231f1f1f&icon.platform=android&icon.query=Keyboard+Arrow&selected=Material+Symbols+Outlined:keyboard_arrow_left:FILL@0;wght@400;GRAD@0;opsz@24
*/
package com.hbcao.rcode.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
public val KeyboardArrowLeft: ImageVector
    get() {
        if (_keyboard_arrow_left != null) {
            return _keyboard_arrow_left!!
        }
        _keyboard_arrow_left =
            ImageVector
                .Builder(
                    name = "keyboard_arrow_left",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                ).apply {
                    path(
                        fill = SolidColor(Color.Black),
                        fillAlpha = 1f,
                        stroke = null,
                        strokeAlpha = 1f,
                        strokeLineWidth = 1f,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Bevel,
                        strokeLineMiter = 1f,
                        pathFillType = PathFillType.Companion.NonZero,
                    ) {
                        moveTo(14f, 18f)
                        lineTo(8f, 12f)
                        lineTo(14f, 6f)
                        lineToRelative(1.4f, 1.4f)
                        lineTo(10.8f, 12f)
                        lineToRelative(4.6f, 4.6f)
                        lineTo(14f, 18f)
                        close()
                    }
                }.build()
        return _keyboard_arrow_left!!
    }

@Suppress("ObjectPropertyName")
private var _keyboard_arrow_left: ImageVector? = null
