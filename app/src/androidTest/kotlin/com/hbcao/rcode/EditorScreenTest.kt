package com.hbcao.rcode

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A0: cold start lands directly on the untitled editor. */
@RunWith(AndroidJUnit4::class)
class EditorScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun coldStartShowsUntitledEditor() {
        composeRule.onNodeWithText("untitled.txt").assertIsDisplayed()
    }
}
