/*
Soirce: https://fonts.google.com/icons?icon.size=24&icon.color=%231f1f1f&icon.platform=android&icon.query=tab&selected=Material+Symbols+Outlined:keyboard_tab:FILL@0;wght@400;GRAD@0;opsz@24
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
public val KeyboardTab: ImageVector
    get() {
        if (_keyboard_tab != null) {
            return _keyboard_tab!!
        }
        _keyboard_tab =
            ImageVector
                .Builder(
                    name = "keyboard_tab",
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
                        moveTo(20f, 18f)
                        verticalLineTo(6f)
                        horizontalLineToRelative(2f)
                        verticalLineTo(18f)
                        horizontalLineTo(20f)
                        close()
                        moveToRelative(-8f, 0f)
                        lineTo(10.58f, 16.6f)
                        lineTo(14.18f, 13f)
                        horizontalLineTo(2f)
                        verticalLineTo(11f)
                        horizontalLineTo(14.18f)
                        lineTo(10.6f, 7.4f)
                        lineTo(12f, 6f)
                        lineToRelative(6f, 6f)
                        lineToRelative(-6f, 6f)
                        close()
                    }
                }.build()
        return _keyboard_tab!!
    }

@Suppress("ObjectPropertyName")
private var _keyboard_tab: ImageVector? = null
