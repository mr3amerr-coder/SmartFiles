package com.example.smartfiles

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import android.app.usage.StorageStatsManager
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.storage.StorageManager
import kotlinx.coroutines.*
import java.io.File

@Composable fun SmartTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current; val dark = isSystemInDarkTheme()
    val cs = if (Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }
             else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = cs, content = content)
}

fun shareFile(c: Context, f: File) {
    val uri = FileProvider.getUriForFile(c, "${c.packageName}.fp", f)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
}

// ---------- Recycle Bin: .SmartTrash/<id>/{origin,data} ----------
val trashRoot: File get() = File(Environment.getExternalStorageDirectory(), ".SmartTrash")

fun moveToTrash(f: File): Boolean {
    val d = File(trashRoot, System.nanoTime().toString())
    if (!d.mkdirs()) return false
    File(d, "origin").writeText(f.path)
    return f.renameTo(File(d, "data")) || run { d.deleteRecursively(); false }
}

class Bin(val dir: File) {
    val orig = File(dir, "origin").takeIf { it.exists() }?.readText() ?: dir.name
    val data = File(dir, "data")
    val name: String get() = File(orig).name
}

fun binItems() = trashRoot.listFiles()?.filter { File(it, "data").exists() }?.map { Bin(it) }?.sortedByDescending { it.dir.lastModified() } ?: emptyList()
fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
fun purgeOldBin() { trashRoot.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 30L * 86_400_000 }?.forEach { it.deleteRecursively() } }

fun restore(b: Bin): Boolean {
    var t = File(b.orig); t.parentFile?.mkdirs()
    if (t.exists()) t = File(t.parentFile, "restored_" + t.name)
    return b.data.renameTo(t).also { if (it) b.dir.deleteRecursively() }
}

@Composable fun TrashScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var empty by remember { mutableStateOf(false) }
    val bin = remember(tick) { binItems() }
    val total = remember(tick) { bin.sumOf { dirSize(it.data) } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Recycle Bin · ${bin.size} items · ${fmt(total)}", style = MaterialTheme.typography.titleMedium)
        Text("Deleted items stay here for 30 days. Empty the Bin to free the space now.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { empty = true }, enabled = bin.isNotEmpty(), modifier = Modifier.padding(vertical = 8.dp)) { Text("Empty Bin & free space") }
        if (bin.isEmpty()) Text("The Bin is empty.")
        LazyColumn(Modifier.weight(1f)) {
            items(bin, key = { it.dir.path }) { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(b.orig.substringBeforeLast('/'), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton({ if (!restore(b)) toast(ctx, "Couldn’t restore"); tick++ }) { Text("Restore") }
                    TextButton({ b.dir.deleteRecursively(); tick++ }) { Text("Delete") }
                }
            }
        }
    }
    if (empty) AlertDialog({ empty = false }, title = { Text("Empty the Bin?") }, text = { Text("${bin.size} items will be permanently deleted.") },
        confirmButton = { TextButton({ trashRoot.deleteRecursively(); empty = false; tick++ }) { Text("Empty") } },
        dismissButton = { TextButton({ empty = false }) { Text("Cancel") } })
}

// ---------- Session state (survives tab switches) ----------
object Session {
    var dir by mutableStateOf(File(Environment.getExternalStorageDirectory().path))
    var cut by mutableStateOf(false)
    val clip = mutableStateListOf<File>()
}

fun pasteInto(dir: File, files: List<File>, cut: Boolean): Int {
    var ok = 0
    for (f in files) {
        if (!f.exists() || (f.isDirectory && (dir.path == f.path || dir.path.startsWith(f.path + "/")))) continue
        val base = f.nameWithoutExtension; val ext = if (f.extension.isEmpty()) "" else "." + f.extension
        var t = File(dir, f.name); var i = 1
        while (t.exists()) { t = File(dir, "$base ($i)$ext"); i++ }
        val done = runCatching { if (cut) f.renameTo(t) || (f.copyRecursively(t) && f.deleteRecursively()) else f.copyRecursively(t) }.getOrDefault(false)
        if (done) ok++
    }
    return ok
}

// ---------- Storage breakdown ----------
fun breakdown(): List<Pair<String, Long>> {
    val m = linkedMapOf("🖼️ Images" to 0L, "🎬 Videos" to 0L, "🎵 Audio" to 0L, "📄 Documents" to 0L, "📦 APKs & archives" to 0L, "📎 Other" to 0L)
    Environment.getExternalStorageDirectory().walkTopDown().onEnter { it.name != ".SmartTrash" }.filter { it.isFile }.forEach { f ->
        val k = when (f.extension.lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "heic" -> "🖼️ Images"
            "mp4", "mkv", "avi", "mov", "3gp" -> "🎬 Videos"
            "mp3", "wav", "m4a", "flac", "ogg", "opus" -> "🎵 Audio"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md" -> "📄 Documents"
            "apk", "zip", "rar", "7z" -> "📦 APKs & archives"
            else -> "📎 Other"
        }
        m[k] = m.getValue(k) + f.length()
    }
    return m.toList().sortedByDescending { it.second }
}

@Composable fun BreakdownCard() {
    var data by remember { mutableStateOf<List<Pair<String, Long>>?>(null) }
    LaunchedEffect(Unit) { data = withContext(Dispatchers.IO) { breakdown() } }
    val d = data
    if (d == null) { Text("Analyzing storage…", style = MaterialTheme.typography.bodySmall); return }
    val max = (d.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
    Text("What’s taking space", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    d.forEach { (n, s) ->
        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n, Modifier.width(140.dp), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { s.toFloat() / max }, Modifier.weight(1f))
            Text(fmt(s), Modifier.padding(start = 8.dp).width(64.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------- Real app size (app + data + cache) when usage access is granted ----------
fun appSize(c: Context, a: ApplicationInfo): Long = runCatching {
    val s = (c.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager)
        .queryStatsForPackage(StorageManager.UUID_DEFAULT, a.packageName, Process.myUserHandle())
    s.appBytes + s.dataBytes
}.getOrElse { File(a.sourceDir).length() }
