/*
Source: https://fonts.google.com/icons?icon.size=24&icon.color=%231f1f1f&icon.platform=android&icon.query=Close&selected=Material+Symbols+Outlined:close:FILL@1;wght@400;GRAD@0;opsz@24
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
public val Close: ImageVector
    get() {
        if (_close != null) {
            return _close!!
        }
        _close =
            ImageVector
                .Builder(
                    name = "close",
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
                        moveTo(6.4f, 19f)
                        lineTo(5f, 17.6f)
                        lineTo(10.6f, 12f)
                        lineTo(5f, 6.4f)
                        lineTo(6.4f, 5f)
                        lineTo(12f, 10.6f)
                        lineTo(17.6f, 5f)
                        lineTo(19f, 6.4f)
                        lineTo(13.4f, 12f)
                        lineTo(19f, 17.6f)
                        lineTo(17.6f, 19f)
                        lineTo(12f, 13.4f)
                        lineTo(6.4f, 19f)
                        close()
                    }
                }.build()
        return _close!!
    }

@Suppress("ObjectPropertyName")
private var _close: ImageVector? = null
