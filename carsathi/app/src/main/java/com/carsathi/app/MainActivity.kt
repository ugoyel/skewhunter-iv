package com.carsathi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carsathi.app.notifications.SystemNotifier
import com.carsathi.app.ui.navigation.CarSathiRoot
import com.carsathi.app.ui.theme.CarSathiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CarSathiApp).container
        splash.setKeepOnScreenCondition { container.session.userState.value == null }
        val openNotifications = intent?.getBooleanExtra(SystemNotifier.EXTRA_OPEN_NOTIFICATIONS, false) == true

        setContent {
            CarSathiTheme {
                val userState by container.session.userState.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val state = userState
                    if (state != null) {
                        // Decide the start destination once; later sign-in/out is handled by navigation.
                        val signedIn = remember { state > 0 }
                        CarSathiRoot(startSignedIn = signedIn, openNotifications = openNotifications)
                    }
                }
            }
        }
    }
}
