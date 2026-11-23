package com.example.smartfiles

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Environment.isExternalStorageManager()) Thread { purgeOldBin() }.start()
        setContent {
            SmartTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

fun fmt(b: Long): String {
    if (b < 1024) return "$b B"
    var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < 3) { v /= 1024; i++ }
    return "%.1f %cB".format(v, "KMGT"[i])
}

fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_SHORT).show()

fun openFile(c: Context, f: File) {
    try {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fp", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: Exception) { toast(c, "No app can open this file") }
}

fun icon(f: File) = if (f.isDirectory) "📁" else when (f.extension.lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp" -> "🖼️"
    "mp4", "mkv", "avi", "mov" -> "🎬"
    "mp3", "wav", "m4a", "flac" -> "🎵"
    "apk" -> "📦"; "pdf", "doc", "docx", "txt", "md" -> "📄"; "zip", "rar", "7z" -> "🗜️"
    else -> "📎"
}

@Composable fun OnResume(cb: () -> Unit) {
    val o = LocalLifecycleOwner.current
    DisposableEffect(o) {
        val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) cb() }
        o.lifecycle.addObserver(ob); onDispose { o.lifecycle.removeObserver(ob) }
    }
}

@Composable fun App() {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    OnResume { ok = Environment.isExternalStorageManager() }
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
        if (ok) NavigationBar {
            listOf("📁 Files", "🧹 Clean", "📱 Apps", "🗑️ Bin", "🎭 Stickers").forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (!ok) Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("Smart Files needs “All files access” to browse and clean your storage.")
                Spacer(Modifier.height(16.dp))
                Button({ ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }) { Text("Grant access") }
            } else when (tab) { 0 -> FilesScreen(); 1 -> CleanScreen(); 2 -> AppsScreen(); 3 -> TrashScreen(); else -> StickersScreen() }
        }
    }
}

// ---------------- FILES ----------------
@OptIn(ExperimentalFoundationApi::class)
@Composable fun FilesScreen() {
    val ctx = LocalContext.current
    val root = Environment.getExternalStorageDirectory()
    var dir by Session::dir
    var tick by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var del by remember { mutableStateOf<File?>(null) }
    var ren by remember { mutableStateOf<File?>(null) }
    var edit by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    var mk by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<File>() }
    var bulk by remember { mutableStateOf(false) }
    BackHandler(dir != root) { dir = dir.parentFile ?: root }
    BackHandler(picked.isNotEmpty()) { picked.clear() }
    val items = remember(dir, tick, sort, query) {
        val cmp: Comparator<File> = when (sort) {
            1 -> compareByDescending { it.length() }
            2 -> compareByDescending { it.lastModified() }
            else -> compareBy { it.name.lowercase() }
        }
        (dir.listFiles()?.toList() ?: emptyList()).filter { it.name.contains(query, true) && it.name != ".SmartTrash" }
            .sortedWith(compareByDescending<File> { it.isDirectory }.then(cmp))
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(dir.path.removePrefix(root.path).ifEmpty { "/" } + "  (internal storage)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Internal" to "", "Downloads" to "Download", "Camera" to "DCIM", "Pictures" to "Pictures", "Documents" to "Documents").forEach { (n, p) ->
                AssistChip(onClick = { picked.clear(); dir = if (p.isEmpty()) root else File(root, p) }, label = { Text(n) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("Search this folder") }, singleLine = true)
            TextButton({ sort = (sort + 1) % 3 }) { Text(listOf("Name", "Size", "Date")[sort]) }
            TextButton({ mk = true }) { Text("＋📁") }
        }
        if (Session.clip.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("📋 ${Session.clip.size} to ${if (Session.cut) "move" else "copy"}", Modifier.weight(1f))
            TextButton({ Session.clip.clear() }) { Text("✕") }
            Button({
                val files = Session.clip.toList(); val cut = Session.cut; val target = dir
                Session.clip.clear()
                scope.launch { val n = withContext(Dispatchers.IO) { pasteInto(target, files, cut) }; toast(ctx, "$n of ${files.size} done"); tick++ }
            }) { Text("Paste here") }
        }
        if (picked.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${picked.size} selected", Modifier.weight(1f))
            TextButton({ picked.clear() }) { Text("✕") }
            TextButton({ Session.clip.clear(); Session.clip.addAll(picked); Session.cut = false; picked.clear() }) { Text("Copy") }
            TextButton({ Session.clip.clear(); Session.clip.addAll(picked); Session.cut = true; picked.clear() }) { Text("Move") }
            Button({ bulk = true }) { Text("Delete") }
        }
        if (items.isEmpty()) Text("Empty folder", Modifier.padding(24.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.path }) { f ->
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().background(if (f in picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .combinedClickable(onLongClick = { if (f in picked) picked.remove(f) else picked.add(f) }) {
                        if (picked.isNotEmpty()) { if (f in picked) picked.remove(f) else picked.add(f) } else if (f.isDirectory) dir = f else openFile(ctx, f)
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(icon(f), Modifier.padding(end = 12.dp), style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = if (f.isDirectory) "${f.listFiles()?.size ?: 0} items" else fmt(f.length())
                        Text(sub + " · " + java.text.SimpleDateFormat("d MMM yyyy").format(f.lastModified()), style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        TextButton({ menu = true }) { Text("⋮") }
                        DropdownMenu(menu, { menu = false }) {
                            if (f.isFile) DropdownMenuItem({ Text("Open") }, { menu = false; openFile(ctx, f) })
                            if (f.isFile) DropdownMenuItem({ Text("Share") }, { menu = false; shareFile(ctx, f) })
                            if (f.isFile && f.length() < 500_000 && f.extension.lowercase() in setOf("txt", "md", "json", "csv", "xml", "log", "html"))
                                DropdownMenuItem({ Text("Edit text") }, { menu = false; edit = f })
                            DropdownMenuItem({ Text("Copy") }, { menu = false; Session.clip.clear(); Session.clip.add(f); Session.cut = false })
                            DropdownMenuItem({ Text("Move") }, { menu = false; Session.clip.clear(); Session.clip.add(f); Session.cut = true })
                            DropdownMenuItem({ Text("Rename") }, { menu = false; ren = f })
                            DropdownMenuItem({ Text("Delete") }, { menu = false; del = f })
                        }
                    }
                }
            }
        }
    }
    del?.let { f ->
        AlertDialog({ del = null }, title = { Text("Delete ${f.name}?") }, text = { Text("It moves to the Bin, where you can restore it for 30 days.") },
            confirmButton = { TextButton({ toast(ctx, if (moveToTrash(f)) "Moved to Bin" else "Couldn’t delete"); del = null; tick++ }) { Text("Delete") } },
            dismissButton = { TextButton({ del = null }) { Text("Cancel") } })
    }
    if (mk) {
        var nm by remember { mutableStateOf("New folder") }
        AlertDialog({ mk = false }, title = { Text("New folder") }, text = { OutlinedTextField(nm, { nm = it }, singleLine = true) },
            confirmButton = { TextButton({ if (!File(dir, nm).mkdirs()) toast(ctx, "Couldn’t create folder"); mk = false; tick++ }) { Text("Create") } },
            dismissButton = { TextButton({ mk = false }) { Text("Cancel") } })
    }
    if (bulk) AlertDialog({ bulk = false }, title = { Text("Move ${picked.size} items to the Bin?") },
        confirmButton = { TextButton({ picked.toList().forEach { moveToTrash(it) }; picked.clear(); bulk = false; tick++ }) { Text("Move") } },
        dismissButton = { TextButton({ bulk = false }) { Text("Cancel") } })
    ren?.let { f ->
        var name by remember { mutableStateOf(f.name) }
        AlertDialog({ ren = null }, title = { Text("Rename") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ if (!f.renameTo(File(f.parentFile, name))) toast(ctx, "Couldn’t rename"); ren = null; tick++ }) { Text("OK") } },
            dismissButton = { TextButton({ ren = null }) { Text("Cancel") } })
    }
    edit?.let { f ->
        var text by remember { mutableStateOf(f.readText()) }
        AlertDialog({ edit = null }, title = { Text(f.name) },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(max = 400.dp)) },
            confirmButton = { TextButton({ f.writeText(text); edit = null; tick++ }) { Text("Save") } },
            dismissButton = { TextButton({ edit = null }) { Text("Cancel") } })
    }
}

// ---------------- CLEAN ----------------
data class Junk(val f: File, val why: String, val safe: Boolean, val size: Long = f.length())

fun md5(f: File): String {
    val d = MessageDigest.getInstance("MD5"); val buf = ByteArray(65536)
    f.inputStream().use { s -> while (true) { val n = s.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
    return d.digest().joinToString("") { "%02x".format(it) }
}

fun scan(): List<Junk> {
    val out = ArrayList<Junk>(); val seen = HashSet<File>(); val sizes = HashMap<Long, MutableList<File>>()
    fun add(f: File, why: String, safe: Boolean) { if (seen.add(f)) out.add(Junk(f, why, safe)) }
    Environment.getExternalStorageDirectory().walkTopDown().onEnter { !it.path.contains("/Android/") && it.name != ".SmartTrash" }.filter { it.isFile }.forEach { f ->
        val e = f.extension.lowercase()
        when {
            e == "apk" -> add(f, "Installer APK (already installed?)", true)
            e in setOf("tmp", "temp", "log", "bak", "old") || f.path.contains("/.thumbnails/") -> add(f, "Temp / cache file", true)
            f.path.contains("/Download/") && System.currentTimeMillis() - f.lastModified() > 90L * 86_400_000 -> add(f, "Old download (90+ days)", false)
            f.length() > 100_000_000 -> add(f, "Large file", false)
        }
        if (f.length() > 1_000_000) sizes.getOrPut(f.length()) { ArrayList() }.add(f)
    }
    sizes.values.filter { it.size > 1 }.forEach { g ->
        g.groupBy { runCatching { md5(it) }.getOrDefault(it.path) }.values.filter { it.size > 1 }.forEach { d ->
            val keep = d.minBy { it.lastModified() }
            d.filter { it != keep }.forEach { add(it, "Duplicate of ${keep.name}", false) }
        }
    }
    return out.sortedByDescending { it.size }
}

@Composable fun CleanScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val st = StatFs(Environment.getExternalStorageDirectory().path)
    val total = st.totalBytes; val free = st.availableBytes
    var junk by remember { mutableStateOf<List<Junk>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val sel = remember { mutableStateListOf<File>() }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Storage", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { (total - free).toFloat() / total }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
        Text("${fmt(total - free)} used of ${fmt(total)} · ${fmt(free)} free")
        if (junk == null) BreakdownCard()
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { scan() }
                    junk = r; sel.clear(); sel.addAll(r.filter { it.safe }.map { it.f }); busy = false
                }
            }) { Text(if (busy) "Scanning…" else "Scan for junk") }
            OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }) { Text("App caches") }
        }
        junk?.let { list ->
            if (list.isEmpty()) Text("Nothing to clean 🎉")
            else Text("Suggested: installers & temp files are pre-selected. Review large files and duplicates before deleting.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.f.path }) { j ->
                    Row(Modifier.fillMaxWidth().clickable { if (j.f in sel) sel.remove(j.f) else sel.add(j.f) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(j.f in sel, { if (it) sel.add(j.f) else sel.remove(j.f) })
                        Column {
                            Text(j.f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${j.why} · ${fmt(j.size)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Button(enabled = sel.isNotEmpty(), onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete ${sel.size} selected (${fmt(list.filter { it.f in sel }.sumOf { it.size })})")
            }
            if (confirm) AlertDialog({ confirm = false }, title = { Text("Delete ${sel.size} files?") }, text = { Text("They go to the Bin. Empty the Bin to actually free the space.") },
                confirmButton = {
                    TextButton({
                        confirm = false
                        scope.launch {
                            withContext(Dispatchers.IO) { sel.toList().forEach { moveToTrash(it) } }
                            junk = list.filter { it.f.exists() }; sel.clear()
                        }
                    }) { Text("Delete") }
                }, dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
        }
    }
}

// ---------------- APPS ----------------
data class AppRow(val label: String, val pkg: String, val size: Long, val last: Long)

fun usageOk(c: Context): Boolean {
    val a = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return a.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
}

fun loadApps(c: Context): List<AppRow> {
    val pm = c.packageManager; val now = System.currentTimeMillis()
    val stats = (c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).queryAndAggregateUsageStats(now - 90L * 86_400_000, now)
    return pm.getInstalledApplications(0)
        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != c.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
        .map { AppRow(pm.getApplicationLabel(it).toString(), it.packageName, appSize(c, it), stats[it.packageName]?.lastTimeUsed ?: 0L) }
        .sortedWith(compareBy<AppRow> { it.last }.thenByDescending { it.size })
}

@Composable fun AppsScreen() {
    val ctx = LocalContext.current
    var hasUsage by remember { mutableStateOf(usageOk(ctx)) }
    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    OnResume { hasUsage = usageOk(ctx); tick++ }
    var bySize by remember { mutableStateOf(false) }
    LaunchedEffect(tick, hasUsage) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    val now = System.currentTimeMillis(); val day = 86_400_000L
    fun stale(a: AppRow) = hasUsage && now - a.last > 30 * day
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (!hasUsage) {
            Text("Allow usage access so Smart Files can spot apps you never open.")
            Button({ ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow usage access") }
        } else Text("${apps.count { stale(it) }} apps unused for 30+ days — good candidates to remove", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f)) {
            item { TextButton({ bySize = !bySize }) { Text(if (bySize) "Sorted by size · tap for unused-first" else "Sorted unused-first · tap for size") } }
            items(if (bySize) apps.sortedByDescending { it.size } else apps, key = { it.pkg }) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val use = if (!hasUsage) "usage unknown" else if (a.last == 0L) "not used in 90+ days" else "last used ${(now - a.last) / day} days ago"
                        Text("${fmt(a.size)} · $use", style = MaterialTheme.typography.bodySmall,
                            color = if (stale(a)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) }) { Text("Info") }
                    TextButton({ ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))) }) { Text("Uninstall") }
                }
            }
        }
    }
}
