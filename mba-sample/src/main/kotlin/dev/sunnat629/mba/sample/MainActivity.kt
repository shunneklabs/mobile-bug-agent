package dev.sunnat629.mba.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sunnat629.mba.core.MBA

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Dashboard()
                }
            }
        }
    }
}

@Composable
private fun Dashboard() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("MBA Sample", style = MaterialTheme.typography.headlineMedium)
        Text("Trigger crashes. Reopen app to process + send to Notion.")

        Button(onClick = { MBA.addBreadcrumb("Tap: Crash (NPE)"); CrashScenarios.npe() }) {
            Text("Crash (NPE)")
        }

        Button(onClick = { MBA.addBreadcrumb("Tap: Crash (IllegalState)"); CrashScenarios.illegalState() }) {
            Text("Crash (IllegalState)")
        }

        Button(onClick = { MBA.addBreadcrumb("Tap: Crash (OOM)"); CrashScenarios.oom() }) {
            Text("Crash (OOM)")
        }

        Button(onClick = {
            MBA.addBreadcrumb("Tap: Non-fatal")
            runCatching { CrashScenarios.nonFatal() }.onFailure { MBA.logError(it) }
        }) {
            Text("Non-fatal error")
        }
    }
}
