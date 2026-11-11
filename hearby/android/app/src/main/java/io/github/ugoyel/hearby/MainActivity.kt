package io.github.ugoyel.hearby

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.ugoyel.hearby.ui.HearbyTheme
import io.github.ugoyel.hearby.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HearbyTheme {
                MainScreen()
            }
        }
    }
}
