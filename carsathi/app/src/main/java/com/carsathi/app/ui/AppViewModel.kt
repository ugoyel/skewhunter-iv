package com.carsathi.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.carsathi.app.AppContainer
import com.carsathi.app.CarSathiApp

/** Creates a ViewModel scoped to the current navigation destination with access to the app container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, noinline create: (AppContainer) -> VM): VM {
    val app = LocalContext.current.applicationContext as CarSathiApp
    return viewModel(key = key, factory = viewModelFactory { initializer { create(app.container) } })
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as CarSathiApp).container
