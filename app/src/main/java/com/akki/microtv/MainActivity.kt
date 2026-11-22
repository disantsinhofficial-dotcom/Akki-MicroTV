package com.akki.microtv

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject

private const val HOME = "https://m.microtv.st/"
private const val MAX_PAGE = 36

data class ShowItem(val title: String, val url: String, val image: String?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFB9A0FF),
                background = Color(0xFF101014),
                surface = Color(0xFF1B1B23)
            )) { MicroTvApp() }
        }
    }
}

private suspend fun fetchPage(page: Int): List<ShowItem> = withContext(Dispatchers.IO) {
    val url = if (page <= 1) HOME else HOME + "?page=" + page
    val doc = Jsoup.connect(url)
        .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        .header("Accept-Language", "en-US,en;q=0.9")
        .timeout(25000)
        .followRedirects(true)
        .get()
    val seen = linkedSetOf<String>()
    doc.select("a[href]").mapNotNull { a ->
        val href = a.absUrl("href").substringBefore("#")
        val title = a.text().trim().replace(Regex("\\s+"), " ")
        val isPost = href.startsWith("https://m.microtv.st/post/") &&
            title.length in 3..140 && !href.contains("?")
        if (isPost && seen.add(href)) {
            val img = a.selectFirst("img")
            val image = img?.let {
                val src = it.absUrl("src").ifBlank { it.absUrl("data-src") }
                src.takeIf { value -> value.startsWith("https://") || value.startsWith("http://") }
            }
            ShowItem(title, href, image)
        } else null
    }
}

@Composable
private fun MicroTvApp() {
    val context = LocalContext.current
    val activity = context as Activity
    var items by remember { mutableStateOf<List<ShowItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<ShowItem?>(null) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("Home") }
    var page by remember { mutableIntStateOf(1) }
    val prefs = remember { context.getSharedPreferences("akki_microtv", 0) }
    var history by remember {
        mutableStateOf(run {
            val raw = prefs.getString("history_items", null)
            if (raw == null) emptyList() else try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    val obj = array.optJSONObject(i) ?: return@mapNotNull null
                    val title = obj.optString("title")
                    val url = obj.optString("url")
                    if (title.isNotBlank() && url.startsWith("https://m.microtv.st/"))
                        ShowItem(title, url, obj.optString("image").takeIf { it.isNotBlank() }) else null
                }
            } catch (_: Exception) { emptyList() }
        })
    }
    var allSearchResults by remember { mutableStateOf<List<ShowItem>?>(null) }
    val scope = rememberCoroutineScope()

    fun load(target: Int) {
        if (loading || target !in 1..MAX_PAGE) return
        scope.launch {
            loading = true
            error = null
            try {
                val result = fetchPage(target)
                if (result.isEmpty()) {
                    error = "Is page par dramas nahi mile. Retry karke dekhein."
                } else {
                    items = result
                    page = target
                }
            } catch (e: Exception) {
                error = "Internet ya MicroTV server se page load nahi hua. Retry karein."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load(1) }
    BackHandler(selected != null) { selected = null }

    if (selected != null) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selected = null }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(
                    selected!!.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
            // Embedded HTML5 player stays inside Akki MicroTV. The site supplies the
            // authorized iframe/player URL; no protected media URL is guessed or extracted.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadsImagesAutomatically = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(true)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        webChromeClient = object : WebChromeClient() {
                            private var customView: View? = null
                            private var customContainer: FrameLayout? = null
                            private var oldSystemUi = 0
                            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                if (customView != null) {
                                    callback.onCustomViewHidden()
                                    return
                                }
                                customView = view
                                oldSystemUi = activity.window.decorView.systemUiVisibility
                                customContainer = FrameLayout(ctx).apply {
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                    addView(view, FrameLayout.LayoutParams(-1, -1))
                                }
                                val decor = activity.window.decorView as FrameLayout
                                decor.addView(customContainer, FrameLayout.LayoutParams(-1, -1))
                                activity.window.decorView.systemUiVisibility =
                                    (View.SYSTEM_UI_FLAG_FULLSCREEN or
                                     View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                     View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                     View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                            }
                            override fun onHideCustomView() {
                                val decor = activity.window.decorView as FrameLayout
                                customContainer?.let { decor.removeView(it) }
                                customContainer = null
                                customView = null
                                activity.window.decorView.systemUiVisibility = oldSystemUi
                            }
                        }
                        loadUrl(selected!!.url)
                    }
                },
                update = { view ->
                    if (view.url != selected!!.url) view.loadUrl(selected!!.url)
                }
            )
        }
    } else {
        Scaffold(
            topBar = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text("AKKI", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("MicroTV", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold)
                    Text("Short dramas • Vertical stories", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        placeholder = { Text("Search dramas") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true, shape = RoundedCornerShape(18.dp)
                    )
                }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == "Home", onClick = { tab = "Home" },
                        icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(selected = tab == "History", onClick = { tab = "History" },
                        icon = { Icon(Icons.Default.History, null) }, label = { Text("History") })
                }
            }
        ) { padding ->
            val sourceItems = if (tab == "History") history else (allSearchResults ?: items)
            val visible = if (tab == "History") sourceItems else sourceItems.filter { it.title.contains(query, true) }
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(if (tab == "History") "Recently opened" else "Explore dramas",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 10.dp)) }
                    if (error != null) TextButton(onClick = { load(page) }) { Text("Try again") }
                    if (tab == "Home" && query.isNotBlank()) {
                        TextButton(onClick = {
                            scope.launch {
                                loading = true
                                error = null
                                try {
                                    val found = linkedMapOf<String, ShowItem>()
                                    for (p in 1..MAX_PAGE) fetchPage(p).forEach { found.putIfAbsent(it.url, it) }
                                    allSearchResults = found.values.toList()
                                } catch (_: Exception) {
                                    error = "Catalogue search complete nahi ho payi. Internet check karke retry karein."
                                } finally { loading = false }
                            }
                        }, enabled = !loading) { Text(if (allSearchResults == null) "Search all 36 pages" else "Refresh all-page results") }
                    }
                }
                items(visible, key = { it.url }) { item ->
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            selected = item
                            history = (listOf(item) + history.filterNot { it.url == item.url }).take(40)
                                                        val saved = JSONArray()
                                                        history.forEach { entry ->
                                                            saved.put(JSONObject().put("title", entry.title).put("url", entry.url).put("image", entry.image ?: ""))
                                                        }
                                                        prefs.edit().putString("history_items", saved.toString()).apply()
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (item.image != null) {
                                coil.compose.AsyncImage(model = item.image, contentDescription = null,
                                    modifier = Modifier.size(76.dp, 96.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleMedium)
                                Text("Tap to play inside Akki MicroTV",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (!loading && visible.isEmpty()) {
                    item { Text(if (query.isNotBlank()) "No matching dramas on this page."
                        else "No history yet.", modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (tab == "Home") {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { load(page - 1) },
                                enabled = page > 1 && !loading) { Text("Previous") }
                            Text("Page $page / $MAX_PAGE", fontWeight = FontWeight.SemiBold)
                            Button(onClick = { load(page + 1) },
                                enabled = page < MAX_PAGE && !loading) { Text("Next Page") }
                        }
                    }
                }
                item {
                    TextButton(onClick = {
                        selected = ShowItem("MicroTV", HOME, null)
                    }) { Text("Open MicroTV website") }
                }
            }
        }
    }
}
