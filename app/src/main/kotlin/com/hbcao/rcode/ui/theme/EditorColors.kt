package com.hbcao.rcode.ui.theme

import androidx.compose.runtime.Immutable

/** Colors the custom editor canvas needs, resolved from the Compose theme. */
@Immutable
data class EditorColors(
    val background: Int,
    val text: Int,
    val caret: Int,
    val composingUnderline: Int,
)
