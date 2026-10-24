package com.necroware.terminusplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import com.necroware.terminusplayer.ui.components.LocalTerminalCursorConfig
import com.necroware.terminusplayer.ui.components.TerminalCursorConfig
import com.necroware.terminusplayer.ui.navigation.TerminusNavGraph
import com.necroware.terminusplayer.ui.theme.TerminusTheme
import com.necroware.terminusplayer.ui.theme.themePresetById
import com.necroware.terminusplayer.ui.theme.withOverrides
import com.necroware.terminusplayer.util.JankStatsHelper
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val initialContentReady = AtomicBoolean(false)

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
            val elapsed = SystemClock.uptimeMillis() - splashStartedAt
            elapsed < MIN_SPLASH_DURATION_MS ||
                (!initialContentReady.get() && elapsed < MAX_SPLASH_WAIT_MS)
        }
        super.onCreate(savedInstanceState)
        JankStatsHelper.initialize(this)

        setContent {
            val activityViewModel: MainActivityViewModel = hiltViewModel()
            val themeState by activityViewModel.themeState.collectAsStateWithLifecycle()
            val motionPreference by activityViewModel.motionPreference.collectAsStateWithLifecycle()

            val themePreset = remember(themeState.themeId, themeState.colorOverrides) {
                themePresetById(themeState.themeId).withOverrides(themeState.colorOverrides)
            }

            TerminusTheme(preset = themePreset) {
                val cursorConfig = remember(themeState.cursorStyle, themeState.cursorBlinkCadence, motionPreference) {
                    TerminalCursorConfig(
                        style = themeState.cursorStyle,
                        blinkCadence = themeState.cursorBlinkCadence,
                        motionPreference = motionPreference
                    )
                }
                CompositionLocalProvider(LocalTerminalCursorConfig provides cursorConfig) {
                    // Expose test tags as resource IDs so :benchmark (UiAutomator) can find them.
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        modifier = @OptIn(ExperimentalComposeUiApi::class) Modifier.semantics {
                            testTagsAsResourceId = true
                        }
                    ) {
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
                        var continueWithoutNotifications by remember { mutableStateOf(false) }

                        val permissionLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestMultiplePermissions()
                        ) { permissions ->
                            hasEssentialPermission = permissions[essentialAudioPermission] ?: isGranted(essentialAudioPermission)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: isGranted(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        val notificationPermissionLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { granted ->
                            hasNotificationPermission = granted
                            if (!granted) continueWithoutNotifications = true
                        }

                        if (hasEssentialPermission && (hasNotificationPermission || continueWithoutNotifications)) {
                            TerminusNavGraph(
                                motionPreference = motionPreference,
                                onInitialContentReady = { initialContentReady.set(true) }
                            )
                        } else if (!hasEssentialPermission) {
                            SideEffect { initialContentReady.set(true) }
                            PermissionGate(onRequestPermission = {
                                permissionLauncher.launch(arrayOf(essentialAudioPermission))
                            })
                        } else {
                            SideEffect { initialContentReady.set(true) }
                            NotificationPermissionGate(
                                onGrant = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                onContinue = { continueWithoutNotifications = true }
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MIN_SPLASH_DURATION_MS = 650L
        const val MAX_SPLASH_WAIT_MS = 3_000L
    }
}

@androidx.compose.runtime.Composable
private fun NotificationPermissionGate(onGrant: () -> Unit, onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("> TRANSFER NOTIFICATIONS", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            "Allow notifications to see upload and download progress while Terminus is in the background. You can continue without them; Android will hide transfer notifications from the drawer, though the active transfer remains in system Active apps.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Button(onClick = onGrant) { Text("ENABLE NOTIFICATIONS") }
        Text("CONTINUE WITHOUT", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp).clickable(onClick = onContinue))
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
            text = "[ audio library access required ]",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Button(onClick = onRequestPermission) {
            Text("GRANT ACCESS")
        }
    }
}
