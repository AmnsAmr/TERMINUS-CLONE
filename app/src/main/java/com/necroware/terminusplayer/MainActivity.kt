package com.necroware.terminusplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.ui.navigation.TerminusNavGraph
import com.necroware.terminusplayer.ui.theme.TerminusTheme
import com.necroware.terminusplayer.ui.theme.themePresetById
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val essentialAudioPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashStartedAt = SystemClock.uptimeMillis()
        installSplashScreen().setKeepOnScreenCondition {
            SystemClock.uptimeMillis() - splashStartedAt < MIN_SPLASH_DURATION_MS
        }
        super.onCreate(savedInstanceState)

        setContent {
            val activityViewModel: MainActivityViewModel = hiltViewModel()
            val themeId by activityViewModel.themeId.collectAsStateWithLifecycle()
            val motionPreference by activityViewModel.motionPreference.collectAsStateWithLifecycle()

            TerminusTheme(preset = themePresetById(themeId)) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var hasEssentialPermission by remember {
                        mutableStateOf(isGranted(essentialAudioPermission))
                    }
                    var hasNotificationPermission by remember {
                        mutableStateOf(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                isGranted(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                true
                            }
                        )
                    }

                    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        hasEssentialPermission = permissions[essentialAudioPermission] ?: isGranted(essentialAudioPermission)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: isGranted(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    if (hasEssentialPermission && hasNotificationPermission) {
                        TerminusNavGraph(motionPreference = motionPreference)
                    } else {
                        PermissionGate(onRequestPermission = {
                            val reqs = mutableListOf(essentialAudioPermission)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                reqs.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permissionLauncher.launch(reqs.toTypedArray())
                        })
                    }
                }
            }
        }
    }

    private companion object {
        const val MIN_SPLASH_DURATION_MS = 650L
    }
}

@androidx.compose.runtime.Composable
private fun PermissionGate(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "> TERMINUS_",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "[ audio library & notification access required ]",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Button(onClick = onRequestPermission) {
            Text("GRANT ACCESS")
        }
    }
}
