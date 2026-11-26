/*
Source: https://fonts.google.com/icons?icon.size=24&icon.color=%231f1f1f&icon.platform=android&icon.query=folder&selected=Material+Symbols+Outlined:folder_open:FILL@1;wght@400;GRAD@0;opsz@24
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
public val FolderOpen: ImageVector
    get() {
        if (_folder_open != null) {
            return _folder_open!!
        }
        _folder_open =
            ImageVector
                .Builder(
                    name = "folder_open",
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
                        moveTo(4f, 20f)
                        quadTo(3.18f, 20f, 2.59f, 19.41f)
                        reflectiveQuadTo(2f, 18f)
                        verticalLineTo(6f)
                        quadTo(2f, 5.18f, 2.59f, 4.59f)
                        reflectiveQuadTo(4f, 4f)
                        horizontalLineToRelative(6f)
                        lineToRelative(2f, 2f)
                        horizontalLineToRelative(8f)
                        quadToRelative(0.83f, 0f, 1.41f, 0.59f)
                        quadTo(22f, 7.18f, 22f, 8f)
                        horizontalLineTo(4f)
                        verticalLineTo(18f)
                        lineTo(6.4f, 10f)
                        horizontalLineTo(23.5f)
                        lineToRelative(-2.57f, 8.57f)
                        quadToRelative(-0.2f, 0.65f, -0.74f, 1.04f)
                        reflectiveQuadTo(19f, 20f)
                        horizontalLineTo(4f)
                        close()
                    }
                }.build()
        return _folder_open!!
    }

@Suppress("ObjectPropertyName")
private var _folder_open: ImageVector? = null
