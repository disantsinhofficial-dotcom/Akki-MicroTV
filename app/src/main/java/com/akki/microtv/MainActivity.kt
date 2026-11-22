package com.akki.microtv

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

private const val HOME = "https://m.microtv.st/"

data class ShowItem(val title: String, val url: String, val image: String?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFFB9A0FF), background = androidx.compose.ui.graphics.Color(0xFF101014), surface = androidx.compose.ui.graphics.Color(0xFF1B1B23))) { MicroTvApp() } }
    }
}

@Composable
private fun MicroTvApp() {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<ShowItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<ShowItem?>(null) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("Home") }
    val prefs = remember { context.getSharedPreferences("akki_microtv", 0) }
    var history by remember { mutableStateOf(prefs.getStringSet("history", emptySet())?.toList() ?: emptyList()) }

    suspend fun loadCatalogue() {
        loading = true
        error = null
        try {
            val found = withContext(Dispatchers.IO) {
                val doc = Jsoup.connect(HOME).userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36").timeout(20000).get()
                val seen = linkedSetOf<String>()
                doc.select("a[href]").mapNotNull { a ->
                    val href = a.absUrl("href")
                    val title = a.text().trim().replace(Regex("\\s+"), " ")
                    if (href.startsWith("https://m.microtv.st/") && href != HOME && title.length >= 3 && title.length <= 100 && seen.add(href)) {
                        val image = a.selectFirst("img")?.absUrl("src")
                        ShowItem(title, href, image)
                    } else null
                }.take(100)
            }
            items = found
            if (found.isEmpty()) error = "Catalogue could not be read from the website. You can still open MicroTV directly."
        } catch (e: Exception) {
            error = "Couldn't load catalogue. Check your internet connection and try again."
        } finally { loading = false }
    }

    LaunchedEffect(Unit) { loadCatalogue() }
    BackHandler(selected != null) { selected = null }
    if (selected != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, "Back") }
                Text(selected!!.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        loadUrl(selected!!.url)
                    }
                },
                update = { view -> if (view.url != selected!!.url) view.loadUrl(selected!!.url) }
            )
        }
    } else {
        Scaffold(
            topBar = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text("AKKI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("MicroTV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text("Short dramas • Vertical stories", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), placeholder = { Text("Search dramas") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
                }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == "Home", onClick = { tab = "Home" }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(selected = tab == "History", onClick = { tab = "History" }, icon = { Icon(Icons.Default.History, null) }, label = { Text("History") })
                }
            }
        ) { padding ->
            val visible = if (tab == "History") items.filter { it.url in history } else items.filter { it.title.contains(query, true) }
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(if (tab == "History") "Recently opened" else "Explore dramas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 10.dp)) }
                    if (error != null) TextButton(onClick = { kotlinx.coroutines.MainScope().launch { loadCatalogue() } }) { Text("Try again") }
                }
                items(visible, key = { it.url }) { item ->
                    Card(Modifier.fillMaxWidth().clickable {
                        selected = item
                        history = (listOf(item.url) + history.filterNot { it == item.url }).take(30)
                        prefs.edit().putStringSet("history", history.toSet()).apply()
                    }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (item.image != null) coil.compose.AsyncImage(model = item.image, contentDescription = null, modifier = Modifier.size(76.dp, 96.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                                Text("Tap to watch on MicroTV", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (!loading && visible.isEmpty()) item { Text(if (query.isNotBlank()) "No matching dramas found." else "No history yet.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { TextButton(onClick = { selected = ShowItem("MicroTV", HOME, null) }) { Text("Open MicroTV website") } }
            }
        }
    }
}
