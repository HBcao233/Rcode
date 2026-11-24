package com.hbcao.rcode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.ViewModelProvider
import com.hbcao.rcode.bridge.UniffiCoreBridge
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
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = MainViewModel(UniffiCoreBridge()) as T
            },
        )[MainViewModel::class.java]
}
