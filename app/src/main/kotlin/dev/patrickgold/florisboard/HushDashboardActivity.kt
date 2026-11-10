package dev.patrickgold.florisboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class HushDashboardActivity : ComponentActivity() {

    // 🚀 FIXED: Tell the Activity how to create the ViewModel with the Room DB Context
    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Install the native Android Splash Screen
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 2. THE GATEKEEPER: Hold the splash screen visible until the ViewModel finishes checking system state
        splashScreen.setKeepOnScreenCondition {
            viewModel.isEvaluating.value
        }

        setContent {
            // Passing the Activity-level ViewModel down to Compose
            HushhCompanionApp(viewModel = viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        // 3. Force a real-time system evaluation every time the app comes to the foreground
        viewModel.evaluateSetupState(this)
    }
}
