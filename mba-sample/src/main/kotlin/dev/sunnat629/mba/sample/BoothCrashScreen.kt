package dev.sunnat629.mba.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sunnat629.mba.core.MBA

@Composable
fun BoothCrashScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF05070B)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF141A2A),
                            Color(0xFF05070B)
                        )
                    )
                )
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Mobile Bug Agent",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Crashlytics catches your crashes. MBA fixes them.",
                    color = Color(0xFFB9C3FF),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(36.dp))

                Button(
                    onClick = {
                        MBA.addBreadcrumb("Booth deterministic crash tapped")
                        StageNpeCrasher.trigger()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7C3AED),
                        contentColor = Color.White,
                    )
                ) {
                    Text(
                        text = "Trigger Demo Crash",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        MBA.addBreadcrumb("Booth chaos fallback crash tapped")
                        error("Chaos fallback crash")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1F2937),
                        contentColor = Color(0xFFE5E7EB),
                    )
                ) {
                    Text(
                        text = "Chaos fallback",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "Scan QR on TV to follow the fix pipeline",
                    color = Color(0xFF9CA3AF),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
