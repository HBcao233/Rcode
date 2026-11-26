/*
Source: https://fonts.google.com/icons?icon.size=24&icon.color=%231f1f1f&icon.platform=android&icon.query=Keyboard+Arrow&selected=Material+Symbols+Outlined:keyboard_arrow_right:FILL@0;wght@400;GRAD@0;opsz@24
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
public val KeyboardArrowRight: ImageVector
    get() {
        if (_keyboard_arrow_right != null) {
            return _keyboard_arrow_right!!
        }
        _keyboard_arrow_right =
            ImageVector
                .Builder(
                    name = "keyboard_arrow_right",
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
                        moveTo(12.6f, 12f)
                        lineTo(8f, 7.4f)
                        lineTo(9.4f, 6f)
                        lineToRelative(6f, 6f)
                        lineToRelative(-6f, 6f)
                        lineTo(8f, 16.6f)
                        lineTo(12.6f, 12f)
                        close()
                    }
                }.build()
        return _keyboard_arrow_right!!
    }

@Suppress("ObjectPropertyName")
private var _keyboard_arrow_right: ImageVector? = null
