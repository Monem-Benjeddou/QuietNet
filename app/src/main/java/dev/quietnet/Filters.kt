package dev.quietnet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class FilterList(
    val id: String,
    val name: String,
    val about: String,
    val url: String,
    val defaultOn: Boolean,
)

/** Public blocklists. They are data maintained by their authors; QuietNet only downloads and reads them. */
object Catalog {
    val all = listOf(
        FilterList(
            "hagezi-pro", "HaGeZi Pro",
            "Ads, trackers, analytics and telemetry. Broad and carefully maintained.",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt", true,
        ),
        FilterList(
            "oisd-big", "OISD Big",
            "Ads, trackers and malware, tuned so sites and apps keep working.",
            "https://big.oisd.nl/domainswild2", true,
        ),
        FilterList(
            "adguard-dns", "AdGuard DNS filter",
            "Ads and trackers, including the ad networks inside mobile apps.",
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", true,
        ),
        FilterList(
            "stevenblack", "StevenBlack hosts",
            "Unified hosts list of ad and malware servers.",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", true,
        ),
        FilterList(
            "pgl", "Peter Lowe's list",
            "Long-running list of ad and tracking servers.",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext", true,
        ),
        FilterList(
            "easylist", "EasyList",
            "Ad servers from the list most browser ad blockers use.",
            "https://easylist.to/easylist/easylist.txt", true,
        ),
        FilterList(
            "easyprivacy", "EasyPrivacy",
            "Tracking servers from the EasyPrivacy list.",
            "https://easylist.to/easylist/easyprivacy.txt", true,
        ),
        FilterList(
            "hagezi-ultimate", "HaGeZi Ultimate",
            "The most aggressive list. Blocks more, but may break some apps and links.",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/ultimate-onlydomains.txt", false,
        ),
        FilterList(
            "hagezi-tif", "Threat protection",
            "Phishing, scam and malware sites. Very large (about 1.8 million domains).",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/tif-onlydomains.txt", false,
        ),
        FilterList(
            "samsung", "Samsung tracking",
            "Samsung's own telemetry and ads on Galaxy phones.",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.samsung-onlydomains.txt", false,
        ),
    )

    val defaults: Set<String> = all.filter { it.defaultOn }.map { it.id }.toSet()
}

object FilterUpdater {
    private const val STALE_MS = 5L * 24 * 60 * 60 * 1000
    private val mutex = Mutex()

    /** A short description of the running update, or null when idle. */
    val progress = MutableStateFlow<String?>(null)
    val lastError = MutableStateFlow<String?>(null)

    fun isStale() = Rules.count == 0 || System.currentTimeMillis() - Prefs.lastUpdate > STALE_MS

    fun updateIfStale(context: Context) {
        if (!isStale() || progress.value != null) return
        val app = context.applicationContext
        App.scope.launch { update(app, force = Prefs.lastUpdate != 0L) }
    }

    /**
     * Downloads the enabled lists and rebuilds the rules. With [force] false,
     * only lists that are missing on disk are downloaded, which makes switching
     * lists on and off quick.
     */
    suspend fun update(context: Context, force: Boolean): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                run(context, force)
            } finally {
                progress.value = null
            }
        }
    }

    private fun run(context: Context, force: Boolean): Boolean {
        val dir = File(context.filesDir, "lists").apply { mkdirs() }
        val enabled = Prefs.enabledLists
        val chosen = Catalog.all.filter { it.id in enabled }
        var failed = 0
        chosen.forEachIndexed { i, list ->
            val f = File(dir, "${list.id}.txt")
            if (force || !f.exists()) {
                progress.value = "Downloading ${list.name} (${i + 1} of ${chosen.size})"
                // One retry covers brief drops, such as the phone locking mid-download.
                if (!download(list.url, f) && !download(list.url, f)) failed++
            }
        }

        progress.value = "Building blocklist"
        val block = LongBuilder()
        val allow = LongBuilder()
        for (list in chosen) {
            val f = File(dir, "${list.id}.txt")
            if (!f.exists()) continue
            var n = 0
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    ListParser.parse(line) { domain, isAllow ->
                        if (isAllow) allow.add(Rules.hash(domain)) else {
                            block.add(Rules.hash(domain)); n++
                        }
                    }
                }
            }
            Prefs.setListCount(list.id, n)
        }
        Rules.set(context, block.sortedUnique(), allow.sortedUnique())
        if (failed == 0) Prefs.lastUpdate = System.currentTimeMillis()
        lastError.value = if (failed > 0) "$failed of ${chosen.size} lists couldn't be downloaded. Check your connection and try again." else null
        return failed == 0
    }

    private fun download(url: String, dest: File): Boolean {
        val tmp = File(dest.path + ".part")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "QuietNet/${BuildConfig.VERSION_NAME} (Android)")
            try {
                if (c.responseCode != 200) return false
                c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            } finally {
                c.disconnect()
            }
            // A tiny file is an error page, not a list; keep the previous copy.
            if (tmp.length() < 1024) false else tmp.renameTo(dest)
        } catch (_: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }
}

/**
 * Reads hosts files ("0.0.0.0 ads.example.com"), plain domain lists, and the
 * domain-only rules of adblock-style lists ("||ads.example.com^"). Rules that
 * need a full URL or the page contents can't be applied to DNS and are skipped.
 */
object ListParser {
    private val okOptions = setOf("important", "all", "document", "doc", "popup", "third-party", "3p")
    private val ignored = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "0.0.0.0",
    )

    fun parse(raw: String, emit: (String, Boolean) -> Unit) {
        val s = raw.trim()
        if (s.isEmpty()) return
        when (s[0]) { '!', '#', '[' -> return }
        if (s.startsWith("@@||")) { abp(s, 4)?.let { emit(it, true) }; return }
        if (s.startsWith("||")) { abp(s, 2)?.let { emit(it, false) }; return }
        if (s.startsWith("@@") || s.contains("##") || s.contains("#@#") || s.contains("#?#") || s.contains("#$#")) return

        val hash = s.indexOf('#')
        val body = if (hash >= 0) s.substring(0, hash) else s
        val parts = body.split(' ', '\t').filter { it.isNotEmpty() }
        when {
            parts.size >= 2 && isAddress(parts[0]) ->
                for (k in 1 until parts.size) clean(parts[k])?.let { emit(it, false) }
            parts.size == 1 -> clean(parts[0])?.let { emit(it, false) }
        }
    }

    private fun abp(s: String, from: Int): String? {
        val caret = s.indexOf('^', from)
        if (caret < 0) return null
        if (caret + 1 < s.length) {
            if (s[caret + 1] != '$') return null
            val options = s.substring(caret + 2).split(',')
            if (options.any { it !in okOptions }) return null
        }
        return clean(s.substring(from, caret))
    }

    fun clean(raw: String): String? {
        var d = raw.lowercase().trimEnd('.')
        if (d.startsWith("*.")) d = d.substring(2)
        if (d.startsWith(".")) d = d.substring(1)
        if (d.length < 4 || d.length > 253 || d in ignored || '.' !in d) return null
        if (d.startsWith('-') || d.contains("..")) return null
        var hasLetter = false
        for (c in d) {
            when (c) {
                in 'a'..'z', '-', '_' -> hasLetter = true
                in '0'..'9', '.' -> {}
                else -> return null
            }
        }
        return if (hasLetter) d else null // skip bare IP addresses
    }

    private fun isAddress(s: String) = s == "::" || s == "::1" || s.all { it.isDigit() || it == '.' }
}

private class LongBuilder {
    private var a = LongArray(1 shl 16)
    private var n = 0

    fun add(v: Long) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }

    fun sortedUnique(): LongArray {
        val x = a.copyOf(n)
        x.sort()
        var w = 0
        for (i in x.indices) if (i == 0 || x[i] != x[i - 1]) x[w++] = x[i]
        return x.copyOf(w)
    }
}
