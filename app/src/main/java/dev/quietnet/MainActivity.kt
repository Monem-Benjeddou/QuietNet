package dev.quietnet

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ENABLE = "enable"
    }

    /** A message for the home screen, such as why protection couldn't start. */
    private val notice = MutableStateFlow<String?>(null)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startBlocking()
        } else {
            notice.value = "QuietNet needs the VPN permission to block ads. " +
                "If another VPN app is set to Always-on, turn that off first."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            QuietTheme {
                MainScreen(notice = notice, onToggle = ::toggle)
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.enabled) FilterUpdater.updateIfStale(this)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ENABLE, false) == true && Blocker.status.value == Status.OFF) turnOn()
    }

    private fun toggle() {
        if (Blocker.status.value == Status.OFF) turnOn() else Blocker.stop(this)
    }

    private fun turnOn() {
        notice.value = null
        val ask = VpnService.prepare(this)
        if (ask == null) {
            startBlocking()
            return
        }
        try {
            vpnPermission.launch(ask)
        } catch (_: ActivityNotFoundException) {
            notice.value = "This phone doesn't allow VPN apps."
        }
    }

    private fun startBlocking() {
        if (!Blocker.start(this)) notice.value = "Couldn't start protection. Please try again."
    }
}
