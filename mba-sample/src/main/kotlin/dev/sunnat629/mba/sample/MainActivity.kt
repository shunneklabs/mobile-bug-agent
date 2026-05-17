package dev.sunnat629.mba.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.sunnat629.mba.sample.ui.theme.MBASampleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MBASampleTheme(darkTheme = true, dynamicColor = false) {
                BoothCrashScreen()
            }
        }
    }
}
