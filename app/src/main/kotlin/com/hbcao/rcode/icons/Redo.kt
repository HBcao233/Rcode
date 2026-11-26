/*
Source: https://fonts.google.com/icons?icon.size=24&icon.color=%231f1f1f&icon.platform=android&icon.query=undo&selected=Material+Symbols+Outlined:redo:FILL@0;wght@400;GRAD@0;opsz@24
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
public val Redo: ImageVector
    get() {
        if (_redo != null) {
            return _redo!!
        }
        _redo =
            ImageVector
                .Builder(
                    name = "redo",
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
                        moveTo(9.9f, 19f)
                        quadTo(7.48f, 19f, 5.74f, 17.43f)
                        reflectiveQuadTo(4f, 13.5f)
                        reflectiveQuadTo(5.74f, 9.57f)
                        reflectiveQuadTo(9.9f, 8f)
                        horizontalLineToRelative(6.3f)
                        lineTo(13.6f, 5.4f)
                        lineTo(15f, 4f)
                        lineToRelative(5f, 5f)
                        lineToRelative(-5f, 5f)
                        lineTo(13.6f, 12.6f)
                        lineTo(16.2f, 10f)
                        horizontalLineTo(9.9f)
                        quadTo(8.33f, 10f, 7.16f, 11f)
                        reflectiveQuadTo(6f, 13.5f)
                        reflectiveQuadTo(7.16f, 16f)
                        reflectiveQuadTo(9.9f, 17f)
                        horizontalLineTo(17f)
                        verticalLineToRelative(2f)
                        horizontalLineTo(9.9f)
                        close()
                    }
                }.build()
        return _redo!!
    }

@Suppress("ObjectPropertyName")
private var _redo: ImageVector? = null
