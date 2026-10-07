package dev.quietnet

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Rules.refreshUser()
        scope.launch(Dispatchers.IO) { Rules.load(this@App) }
    }

    companion object {
        /** Work that must outlive a screen, such as downloading filter lists. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
