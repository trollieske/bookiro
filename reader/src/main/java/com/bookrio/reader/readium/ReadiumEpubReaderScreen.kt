@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bookrio.reader.readium

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Readium-backed EPUB reader: opens the publication with Readium and renders it
 * with the stable [EpubNavigatorFragment] hosted inside Compose. Bookiro owns the
 * chrome (top/bottom bars, contents sheet, preferences) but NOT pagination —
 * Readium lays out and paginates the EPUB itself.
 *
 * Reading position is a Readium [Locator]; it is persisted additively in the
 * existing `reading_progress.anchor_cfi` column as Locator JSON, with
 * `progress_percent` from the total progression. Old pageIndex-only progress is
 * not blindly mapped to a page number (pagination is not stable after reflow);
 * when there is no stored Locator the book opens at the start and the limitation
 * is recorded in reader/README-READIUM.md.
 */
@Composable
internal fun ReadiumEpubReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val db = remember { ShelfDatabase.getInstance(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var publication by remember { mutableStateOf<Publication?>(null) }
    var initialLocator by remember { mutableStateOf<Locator?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var bookTitle by remember { mutableStateOf("") }
    var showControls by remember { mutableStateOf(true) }
    var showContents by remember { mutableStateOf(false) }
    var fontSize by remember { mutableStateOf(1.0) }
    var theme by remember { mutableStateOf(Theme.DARK) }
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var progressPercent by remember { mutableStateOf(0) }

    LaunchedEffect(bookId) {
        val book = runCatching { db.bookDao().getById(bookId) }.getOrNull()
        bookTitle = book?.title ?: ""
        val filePath = book?.filePath
        if (filePath.isNullOrBlank()) {
            error = "Boken mangler en lesbar fil på denne enheten."
            return@LaunchedEffect
        }
        val saved = runCatching { db.progressDao().getByBook(bookId) }.getOrNull()
        progressPercent = ((saved?.progressPercent ?: 0f) * 100).toInt()
        initialLocator = saved?.anchorCfi?.let { json ->
            runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
        }
        ReadiumPublicationOpener.open(context.applicationContext, filePath)
            .onSuccess { publication = it }
            .onFailure { error = it.message ?: "Kan ikke åpne boken" }
    }

    fun applyPrefs() {
        navigator?.submitPreferences(EpubPreferences(fontSize = fontSize, theme = theme))
    }

    Box(modifier = Modifier.fillMaxSize().background(OmarchyColors.Bg)) {
        when {
            error != null -> ReaderMessage(error!!, onBack)
            publication == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = OmarchyColors.Accent)
            }
            else -> {
                val pub = publication!!
                if (activity == null) {
                    ReaderMessage("Leseren krever en FragmentActivity.", onBack)
                } else {
                    val factory = remember(pub) {
                        EpubNavigatorFactory(pub).createFragmentFactory(
                            initialLocator = initialLocator,
                            initialPreferences = EpubPreferences(fontSize = fontSize, theme = theme),
                        )
                    }
                    DisposableEffect(factory) {
                        activity.supportFragmentManager.fragmentFactory = factory
                        onDispose { }
                    }
                    AndroidFragment(
                        EpubNavigatorFragment::class.java,
                        modifier = Modifier.fillMaxSize(),
                    ) { fragment ->
                        navigator = fragment
                    }
                    LaunchedEffect(navigator) {
                        navigator?.currentLocator?.collect { locator ->
                            progressPercent =
                                ((locator.locations.totalProgression ?: 0.0) * 100).toInt()
                            persistLocator(db, bookId, locator, scope)
                        }
                    }
                }

                // Tap in the top / bottom margins toggles the chrome without
                // covering the text surface (Readium owns the reading area).
                if (showControls) {
                    ReaderChrome(
                        title = bookTitle,
                        percent = progressPercent,
                        onBack = onBack,
                        onContents = { showContents = true },
                        onFontDown = { fontSize = (fontSize - 0.1).coerceAtLeast(0.6); applyPrefs() },
                        onFontUp = { fontSize = (fontSize + 0.1).coerceAtMost(3.0); applyPrefs() },
                        onTheme = {
                            theme = when (theme) {
                                Theme.DARK -> Theme.LIGHT
                                Theme.LIGHT -> Theme.SEPIA
                                Theme.SEPIA -> Theme.DARK
                            }
                            applyPrefs()
                        },
                        onHide = { showControls = false },
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { showControls = true },
                    )
                }
            }
        }
    }

    if (showContents && publication != null) {
        val toc = publication!!.tableOfContents
        ModalBottomSheet(
            onDismissRequest = { showContents = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = OmarchyColors.Panel,
            contentColor = OmarchyColors.Fg,
        ) {
            Text(
                "Innhold",
                style = ShelfTypography.TitleMedium,
                color = OmarchyColors.FgBright,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn {
                items(flattenToc(toc), key = { it.first.href.toString() + it.second }) { (link, depth) ->
                    Text(
                        text = link.title ?: link.href.toString(),
                        color = if (depth == 0) OmarchyColors.FgBright else OmarchyColors.Dim,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showContents = false
                                navigator?.go(link, animated = true)
                            }
                            .padding(start = (20 + depth * 16).dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                    )
                }
            }
        }
    }
}

private fun flattenToc(links: List<Link>, depth: Int = 0): List<Pair<Link, Int>> =
    links.flatMap { link -> listOf(link to depth) + flattenToc(link.children, depth + 1) }

private fun persistLocator(
    db: ShelfDatabase,
    bookId: Long,
    locator: Locator,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val json = runCatching { locator.toJSON().toString() }.getOrNull() ?: return
    val pct = (locator.locations.totalProgression ?: locator.locations.progression ?: 0.0).toFloat()
    scope.launch(Dispatchers.IO) {
        runCatching {
            val prior = db.progressDao().getByBook(bookId) ?: ReadingProgressEntity(bookId = bookId)
            db.progressDao().insertOrReplace(
                prior.copy(
                    anchorCfi = json,
                    anchorHref = locator.href.toString(),
                    progressPercent = pct.coerceIn(0f, 1f),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}

@Composable
private fun ReaderChrome(
    title: String,
    percent: Int,
    onBack: () -> Unit,
    onContents: () -> Unit,
    onFontDown: () -> Unit,
    onFontUp: () -> Unit,
    onTheme: () -> Unit,
    onHide: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OmarchyColors.Panel)
                .clickable { onHide() }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tilbake", tint = OmarchyColors.Accent)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = OmarchyColors.FgBright, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 15.sp)
                Text("$percent %", color = OmarchyColors.Dim, fontSize = 11.sp)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OmarchyColors.Panel)
                .clickable { onHide() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ChromeButton("Innhold", Icons.Default.Menu, onContents)
            ChromeButton("A-", Icons.Default.FormatSize, onFontDown)
            ChromeButton("A+", Icons.Default.FormatSize, onFontUp)
            ChromeButton("Tema", Icons.Default.Palette, onTheme)
        }
    }
}

@Composable
private fun ChromeButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Icon(icon, contentDescription = label, tint = OmarchyColors.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = OmarchyColors.Dim, fontSize = 10.sp)
    }
}

@Composable
private fun ReaderMessage(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = OmarchyColors.Fg, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        Text("Tilbake", color = OmarchyColors.Accent, fontSize = 14.sp, modifier = Modifier.clickable { onBack() })
    }
}