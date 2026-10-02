package io.github.junwen853.cloudtransfer

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.junwen853.cloudtransfer.backup.BackupState
import io.github.junwen853.cloudtransfer.data.PlanCategory
import io.github.junwen853.cloudtransfer.ui.LoginScreen
import io.github.junwen853.cloudtransfer.ui.MainScreen
import io.github.junwen853.cloudtransfer.ui.PlanListScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    // "main", "login" or a PlanCategory name.
                    var screen by rememberSaveable { mutableStateOf("main") }
                    BackHandler(enabled = screen != "main") { screen = "main" }
                    when (screen) {
                        "main" -> MainScreen(onLogin = { screen = "login" }, onOpenList = { screen = it.name })
                        "login" -> LoginScreen { message ->
                            message?.let(BackupState::log)
                            screen = "main"
                        }
                        else -> PlanListScreen(PlanCategory.valueOf(screen)) { screen = "main" }
                    }
                }
            }
        }
    }
}
