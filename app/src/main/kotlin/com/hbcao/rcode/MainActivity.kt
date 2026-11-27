package com.hbcao.rcode

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hbcao.rcode.bridge.UniffiCoreBridge
import com.hbcao.rcode.bridge.UniffiEditorSession
import com.hbcao.rcode.platform.document.ContentResolverDocumentIo
import com.hbcao.rcode.state.MainViewModel
import com.hbcao.rcode.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    MainScreen(viewModel = mainViewModel())
                }
            }
        }
    }

    private fun mainViewModel(): MainViewModel =
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = newMainViewModel() as T
            },
        )[MainViewModel::class.java]

    private fun newMainViewModel(): MainViewModel =
        MainViewModel(
            bridge = UniffiCoreBridge(),
            editorSession = UniffiEditorSession(),
            // Only the platform layer touches ContentResolver (architecture.md §2).
            documentIo = ContentResolverDocumentIo(contentResolver),
            logger = { message, error ->
                if (error != null) Log.e(LOG_TAG, message, error) else Log.i(LOG_TAG, message)
            },
        )

    private companion object {
        const val LOG_TAG = "rcode-open"
    }
}
