package dev.quietnet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.MutableStateFlow

enum class Status { OFF, STARTING, ON }

class Hit(val id: Long, val domain: String, val time: Long, val blocked: Boolean)

/** State shared between the blocking service and the screens, which run in one process. */
object Blocker {
    private const val RECENT_MAX = 300

    val status = MutableStateFlow(Status.OFF)
    val blocked = MutableStateFlow(0L)
    val checked = MutableStateFlow(0L)
    val recent = MutableStateFlow<List<Hit>>(emptyList())
    val ruleCount = MutableStateFlow(0)
    val userRules = MutableStateFlow<Pair<Set<String>, Set<String>>>(emptySet<String>() to emptySet())
    private var nextId = 0L

    /** Starts blocking. The caller must already hold the VPN permission. */
    fun start(context: Context): Boolean = try {
        Prefs.enabled = true
        if (status.value == Status.OFF) {
            blocked.value = 0
            checked.value = 0
        }
        context.startService(Intent(context, BlockerService::class.java).setAction(BlockerService.ACTION_START))
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun stop(context: Context) {
        Prefs.enabled = false
        send(context, BlockerService.ACTION_STOP)
    }

    /** Rebuilds the tunnel, for settings such as excluded apps that only apply when it starts. */
    fun restart(context: Context) {
        if (status.value != Status.OFF) send(context, BlockerService.ACTION_RESTART)
    }

    private fun send(context: Context, action: String) {
        try {
            context.startService(Intent(context, BlockerService::class.java).setAction(action))
        } catch (_: Exception) {
        }
    }

    fun setStatus(context: Context, s: Status) {
        status.value = s
        try {
            TileService.requestListeningState(context, ComponentName(context, ToggleTile::class.java))
        } catch (_: Exception) {
        }
    }

    /** Called only from the tunnel's reader thread. */
    fun record(domain: String, wasBlocked: Boolean) {
        checked.value += 1
        if (wasBlocked) blocked.value += 1
        val old = recent.value
        // Apps often ask for the same name twice in a row (A and AAAA); show it once.
        if (old.isNotEmpty() && old[0].domain == domain && old[0].blocked == wasBlocked) return
        val next = ArrayList<Hit>(minOf(old.size + 1, RECENT_MAX))
        next.add(Hit(nextId++, domain, System.currentTimeMillis(), wasBlocked))
        for (i in 0 until minOf(old.size, RECENT_MAX - 1)) next.add(old[i])
        recent.value = next
    }
}
