package com.mrashidcit.project4one_to_onevideocall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mrashidcit.project4one_to_onevideocall.presentation.call.CallScreen
import com.mrashidcit.project4one_to_onevideocall.ui.theme.Project4OnetoOneVideoCallTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-Activity app. Compose UI + navigation reduces to just one screen for this project
 * (see README > "Do Not Overengineer") - [CallScreen] internally switches between the
 * pre-join form and the in-call UI based on [com.mrashidcit.project4one_to_onevideocall.domain.model.CallState].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }
}

@Composable
private fun AppRoot() {
    Project4OnetoOneVideoCallTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            CallScreen()
        }
    }
}
