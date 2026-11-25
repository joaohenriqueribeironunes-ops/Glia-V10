package com.glia.assistant

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glia.ui.GliaApp
import com.glia.ui.theme.GliaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: GliaViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    private var dialog by mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                viewModel.events.collect { event -> handleEvent(event) }
            }

            val settings = state.settings
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !settings.darkTheme
                isAppearanceLightNavigationBars = !settings.darkTheme
            }

            GliaTheme(
                darkTheme = settings.darkTheme,
                glass = settings.glass,
                animations = settings.animations,
            ) {
                GliaApp(state = state, actions = viewModel.actions)

                dialog?.let { (title, message) ->
                    AlertDialog(
                        onDismissRequest = { dialog = null },
                        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
                        confirmButton = {
                            TextButton(onClick = { dialog = null }) {
                                Text("Entendi", color = Color(0xFF9ED9A9))
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true) {
            intent.removeExtra(EXTRA_LISTEN)
            window.decorView.post { viewModel.actions.onMicToggle() }
        }
    }

    private fun handleEvent(event: AppEvent) {
        when (event) {
            is AppEvent.RequestPermissions -> permissionLauncher.launch(event.permissions)
            is AppEvent.OpenSystemSettings -> {
                val intent = Intent(event.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (event.withPackage) {
                    intent.data = Uri.parse("package:$packageName")
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startSafely(intent)
            }
            is AppEvent.OpenWriteSettingsSettings -> startSafely(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData(Uri.parse("package:${event.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            AppEvent.OpenAutostart -> openAutostart()
            is AppEvent.ShowText -> dialog = event.title to event.message
            is AppEvent.Message -> Toast.makeText(this, event.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun openAutostart() {
        val miui = Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!startSafely(miui)) {
            startSafely(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Toast.makeText(
                this,
                "Abra Permissões → Autostart no HyperOS para liberar a Glia.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun startSafely(intent: Intent): Boolean = runCatching {
        startActivity(intent)
    }.isSuccess

    companion object {
        const val EXTRA_LISTEN = "com.glia.assistant.extra.LISTEN"
    }
}
