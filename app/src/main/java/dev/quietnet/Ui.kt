package dev.quietnet

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

// ---------- Theme ----------

private val Brand = Color(0xFF0B6E4F)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2CF),
    onPrimaryContainer = Color(0xFF002115),
    background = Color(0xFFF6FAF7),
    surface = Color(0xFFF6FAF7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD8B0),
    onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF005139),
    onPrimaryContainer = Color(0xFFA6F2CF),
    background = Color(0xFF0F1412),
    surface = Color(0xFF0F1412),
)

@Composable
fun QuietTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors: ColorScheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

// ---------- Shell ----------

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Shield),
    Activity("Activity", Icons.Rounded.History),
    Filters("Filters", Icons.Rounded.FilterList),
    Apps("Apps", Icons.Rounded.Apps),
}

@Composable
fun MainScreen(notice: StateFlow<String?>, onToggle: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Home -> HomeScreen(notice, onToggle)
                Tab.Activity -> ActivityScreen()
                Tab.Filters -> FiltersScreen()
                Tab.Apps -> AppsScreen()
            }
        }
    }
}

// ---------- Home ----------

@Composable
private fun HomeScreen(notice: StateFlow<String?>, onToggle: () -> Unit) {
    val status by Blocker.status.collectAsState()
    val blocked by Blocker.blocked.collectAsState()
    val checked by Blocker.checked.collectAsState()
    val rules by Blocker.ruleCount.collectAsState()
    val progress by FilterUpdater.progress.collectAsState()
    val error by FilterUpdater.lastError.collectAsState()
    val message by notice.collectAsState()
    val on = status == Status.ON
    val context = LocalContext.current
    val strictDns = remember(status) { privateDnsHostname(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text("QuietNet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(36.dp))
        PowerButton(on = on, busy = status == Status.STARTING, onClick = onToggle)
        Spacer(Modifier.height(28.dp))
        Text(
            if (on) "Ads are blocked" else "Protection is off",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (on) "In every app and browser on this phone" else "Tap the shield to block ads on this phone",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        progress?.let {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Blocked", number(blocked), Modifier.weight(1f))
            Stat("Checked", number(checked), Modifier.weight(1f))
            Stat("Blocklist", if (rules == 0) "–" else compact(rules), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        YouTubeCard { context.startActivity(Intent(context, YouTubeActivity::class.java)) }
        message?.let { Notice(Icons.Rounded.Warning, it) }
        if (on && strictDns != null) {
            Notice(
                Icons.Rounded.Warning,
                "Private DNS is set to $strictDns, which skips QuietNet, so ads aren't blocked. " +
                    "Set Private DNS to Off or Automatic.",
                action = "Open settings",
                onAction = { openSettings(context) },
            )
        }
        error?.let { Notice(Icons.Rounded.Warning, it) }
        Notice(
            Icons.Rounded.Info,
            "Ads inside the YouTube, Instagram and Facebook apps come from the same servers as the videos " +
                "and posts, so no app can block them there without root. Watch YouTube here instead.",
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PowerButton(on: Boolean, busy: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(if (on) scheme.primary else scheme.surfaceVariant, label = "bg")
    val foreground by animateColorAsState(if (on) scheme.onPrimary else scheme.onSurfaceVariant, label = "fg")
    val glow by animateFloatAsState(if (on) 1f else 0f, label = "glow")
    Box(Modifier.size(224.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(224.dp).scale(0.8f + 0.2f * glow).clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.14f * glow)),
        )
        Surface(
            onClick = onClick,
            enabled = !busy,
            shape = CircleShape,
            color = background,
            shadowElevation = if (on) 6.dp else 1.dp,
            modifier = Modifier.size(172.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(color = foreground)
                } else {
                    Icon(
                        painterResource(R.drawable.ic_shield),
                        contentDescription = if (on) "Turn off ad blocking" else "Turn on ad blocking",
                        tint = foreground,
                        modifier = Modifier.size(84.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun YouTubeCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_play),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("YouTube without ads", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Keeps playing with the screen off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(vertical = 14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (action != null) {
                    TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(action) }
                }
            }
        }
    }
}

// ---------- Activity ----------

@Composable
private fun ActivityScreen() {
    val recent by Blocker.recent.collectAsState()
    val user by Blocker.userRules.collectAsState()
    val status by Blocker.status.collectAsState()
    var onlyBlocked by rememberSaveable { mutableStateOf(true) }
    val shown = if (onlyBlocked) recent.filter { it.blocked } else recent

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Header(
                "Activity",
                "Recent lookups by apps on this phone. Allow anything that stopped working, " +
                    "or block an ad server that got through.",
            )
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = onlyBlocked, onClick = { onlyBlocked = true }, label = { Text("Blocked") })
                FilterChip(selected = !onlyBlocked, onClick = { onlyBlocked = false }, label = { Text("All") })
            }
            Spacer(Modifier.height(8.dp))
        }
        if (shown.isEmpty()) {
            item {
                Empty(
                    when {
                        status == Status.OFF -> "Turn on protection to see what gets blocked."
                        onlyBlocked -> "Nothing blocked yet. Open an app or website with ads."
                        else -> "No lookups yet."
                    },
                )
            }
        }
        items(shown, key = { it.id }) { hit ->
            val (allowSet, blockSet) = user
            val nowBlocked = remember(user, hit.domain) { Rules.isBlocked(hit.domain) }
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (hit.blocked) Icons.Rounded.Block else Icons.Rounded.CheckCircle,
                    contentDescription = if (hit.blocked) "Blocked" else "Allowed",
                    tint = if (hit.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(hit.domain, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        clock(hit.time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    hit.domain in blockSet -> TextButton(onClick = { Rules.forget(hit.domain) }) { Text("Unblock") }
                    hit.domain in allowSet -> TextButton(onClick = { Rules.forget(hit.domain) }) { Text("Undo allow") }
                    nowBlocked -> TextButton(onClick = { Rules.allowDomain(hit.domain) }) { Text("Allow") }
                    else -> TextButton(onClick = { Rules.blockDomain(hit.domain) }) { Text("Block") }
                }
            }
        }
    }
}

// ---------- Filters ----------

@Composable
private fun FiltersScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by FilterUpdater.progress.collectAsState()
    val error by FilterUpdater.lastError.collectAsState()
    val rules by Blocker.ruleCount.collectAsState()
    val user by Blocker.userRules.collectAsState()
    var enabled by remember { mutableStateOf(Prefs.enabledLists) }
    var upstream by remember { mutableStateOf(Prefs.upstream) }
    var newDomain by rememberSaveable { mutableStateOf("") }

    fun rebuild(force: Boolean) {
        scope.launch { FilterUpdater.update(context.applicationContext, force) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Header("Filters", "Lists of ad, tracker and malware servers. More lists block more, but can break some apps.")
            Card(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (rules == 0) "No blocklist yet" else "${number(rules.toLong())} domains blocked",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            progress ?: error ?: if (Prefs.lastUpdate == 0L) "Lists download when you turn on protection"
                            else "Updated ${ago(Prefs.lastUpdate)}. Updates every few days.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    if (progress != null) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    } else {
                        FilledTonalButton(onClick = { rebuild(force = true) }) { Text("Update") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        items(Catalog.all, key = { it.id }) { list ->
            val on = list.id in enabled
            val count = Prefs.listCount(list.id)
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = progress == null) {
                        enabled = if (on) enabled - list.id else enabled + list.id
                        Prefs.enabledLists = enabled
                        rebuild(force = false)
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(list.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        list.about + if (on && count > 0) " ${compact(count)} domains." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = on, onCheckedChange = null, enabled = progress == null)
            }
        }

        item {
            Section("Your rules")
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                val add = {
                    ListParser.clean(newDomain.trim().removePrefix("https://").removePrefix("http://").substringBefore('/'))
                        ?.let { Rules.blockDomain(it); newDomain = "" }
                    Unit
                }
                OutlinedTextField(
                    value = newDomain,
                    onValueChange = { newDomain = it },
                    placeholder = { Text("ads.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Button(onClick = add, enabled = newDomain.isNotBlank()) { Text("Block") }
            }
            Spacer(Modifier.height(6.dp))
            if (user.first.isEmpty() && user.second.isEmpty()) {
                Text(
                    "Domains you block or allow, here or from Activity, show up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        items(user.second.sorted(), key = { "b:$it" }) { RuleRow(it, blocked = true) }
        items(user.first.sorted(), key = { "a:$it" }) { RuleRow(it, blocked = false) }

        item {
            Section("DNS server")
            Text(
                "Where lookups that aren't blocked are sent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        items(Upstream.options, key = { "u:${it.id}" }) { option ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = upstream == option.id, role = Role.RadioButton) {
                        upstream = option.id
                        Prefs.upstream = option.id
                    }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = upstream == option.id, onClick = null, modifier = Modifier.padding(12.dp))
                Column {
                    Text(option.name, style = MaterialTheme.typography.bodyLarge)
                    Text(option.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Section("About")
            Text(
                "QuietNet ${BuildConfig.VERSION_NAME}. It filters lookups on this phone and sends nothing anywhere " +
                    "except unblocked lookups to the DNS server above. Blocklists are downloaded from their maintainers: " +
                    "HaGeZi, OISD, AdGuard, StevenBlack, Peter Lowe and EasyList.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun RuleRow(domain: String, blocked: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (blocked) Icons.Rounded.Block else Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(domain, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (blocked) "Always blocked" else "Always allowed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { Rules.forget(domain) }) { Icon(Icons.Rounded.Close, contentDescription = "Remove rule") }
    }
}

// ---------- Apps ----------

private class AppEntry(val pkg: String, val label: String)

@Composable
private fun AppsScreen() {
    val context = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) { value = withContext(Dispatchers.IO) { launchableApps(context) } }
    var excluded by remember { mutableStateOf(Prefs.excludedApps) }
    var query by rememberSaveable { mutableStateOf("") }

    // Excluded apps only change when the tunnel is rebuilt; wait for the user to finish toggling.
    LaunchedEffect(excluded) {
        if (excluded == Prefs.excludedApps) return@LaunchedEffect
        Prefs.excludedApps = excluded
        delay(1200)
        Blocker.restart(context)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Header(
                "Apps",
                "Ads are blocked in every app. If an app stops working, such as a banking app, turn it off here and it skips QuietNet.",
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Search apps") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        val list = apps
        if (list == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
        } else {
            val q = query.trim()
            val shown = list.filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) }
                .sortedBy { it.pkg !in excluded } // skipped apps first
            items(shown, key = { it.pkg }) { app ->
                val filtered = app.pkg !in excluded
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { excluded = if (filtered) excluded + app.pkg else excluded - app.pkg }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app.pkg)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (filtered) "Ads blocked" else "Skips QuietNet",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (filtered) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    Switch(checked = filtered, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            try {
                val d = context.packageManager.getApplicationIcon(pkg)
                val size = 96
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, size, size)
                d.draw(Canvas(bmp))
                bmp.asImageBitmap()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

private fun launchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != context.packageName }
        .distinctBy { it.first }
        .map { AppEntry(it.first, it.second) }
        .sortedBy { it.label.lowercase() }
}

// ---------- Shared pieces ----------

@Composable
private fun Header(title: String, subtitle: String) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    )
}

private fun number(n: Long): String = NumberFormat.getIntegerInstance().format(n)

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000}K"
    else -> n.toString()
}

private fun clock(time: Long): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(time))

private fun ago(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** The Private DNS hostname when it's set to a specific provider, which bypasses the tunnel. */
private fun privateDnsHostname(context: Context): String? {
    if (Build.VERSION.SDK_INT < 28) return null
    val cm = context.getSystemService(ConnectivityManager::class.java)
    return cm.getLinkProperties(cm.activeNetwork)?.privateDnsServerName
}

private fun openSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}
