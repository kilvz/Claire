package com.wangzan101.mika.browser.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.wangzan101.mika.ui.components.MikaTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.browser.CustomSearchEngine
import com.wangzan101.mika.browser.FavoriteSite
import com.wangzan101.mika.browser.PageBookmark
import com.wangzan101.mika.browser.RecentItem
import com.wangzan101.mika.browser.SearchEngine
import com.wangzan101.mika.browser.SearchEngineSpec
import com.wangzan101.mika.browser.SiteFace
import com.wangzan101.mika.browser.StartPageModel
import com.wangzan101.mika.browser.Suggestion
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSpace
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.settings.ui.models.Page

val BROWSER_SEARCH_HINT: String get() = tr(R.string.browser_search_hint)
val BROWSER_LOGIN_LEGEND: String get() = tr(R.string.browser_login_legend)
val BROWSER_ADD_HINT: String get() = tr(R.string.browser_add_hint)
private const val COLUMNS = 4

/** How many bookmarks the start page lists at most; the rest are under "all". */
private const val MAX_BOOKMARKS = 5

/**
 * The browser's start page: the top bar has back, downloads, more and "Browser"; a large search box (searches with [engine], and the small line
 * below opens a menu to switch engines or add your own),
 * while typing, suggestions appear below (first searching this phrase or opening this URL, then matches among recently opened pages);
 * "favorites" in rows of four (icon, name, a small green dot when logged in, and a "+" in the last cell), entering edit mode via "edit" or a long
 * press (icons shake gently, × removes, "done" exits);
 * "bookmarks" shows the few most recently added, and "all" opens the bookmarks page;
 * "recently opened" shows the last 10 (time on the right, "all" next to the title opens the full history). While editing or typing, back first exits those.
 */
@Composable
fun BrowserStart(
    sites: List<FavoriteSite>,
    recent: List<RecentItem>,
    face: (String) -> SiteFace,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenSite: (FavoriteSite) -> Unit,
    onRemoveSite: (FavoriteSite) -> Unit,
    onAddHint: () -> Unit,
    onClearRecent: () -> Unit = {},
    bookmarks: List<PageBookmark> = emptyList(),
    onOpenBookmark: (PageBookmark) -> Unit = {},
    onShowBookmarks: () -> Unit = {},
    onDownloads: () -> Unit = {},
    tabsCount: Int = 0,
    onMenu: () -> Unit = {},
    onHistory: () -> Unit = {},
    engine: SearchEngineSpec = SearchEngine.Bing,
    custom: List<CustomSearchEngine> = emptyList(),
    onPickEngine: (SearchEngineSpec) -> Unit = {},
    onAddEngine: (CustomSearchEngine) -> Unit = {},
    onRemoveEngine: (CustomSearchEngine) -> Unit = {},
    now: Long = System.currentTimeMillis(),
) {
    val c = MikaTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    var pickingEngine by rememberSaveable { mutableStateOf(false) }
    var addingEngine by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = editing || query.isNotEmpty()) {
        if (editing) editing = false else query = ""
    }
    Column(Modifier.fillMaxSize()) {
        if (editing) {
            TopBar(tr(R.string.browser_edit_favorites), right = {
                Text(
                    tr(R.string.done), Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button) { editing = false }.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MikaType.BodyStrong, color = c.accent,
                )
            })
        } else {
            TopBar(tr(R.string.drawer_browser), MikaIcons.Back, onBack, tr(R.string.back), right = {
                BarIconButton(MikaIcons.Download, tr(R.string.browser_downloads), onDownloads)
                BarIconButton(MikaIcons.More, tr(R.string.more), onMenu, badge = tabsCount > 0)
            })
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = MikaSpace.Page),
        ) {
            if (editing) {
                SectionHeader(tr(R.string.browser_tap_x))
                SiteGrid(sites, face, editing = true, onOpen = {}, onRemove = onRemoveSite, onEdit = {}, onAdd = null)
                Legend(tr(R.string.browser_long_press), dot = false)
            } else {
                Spacer(Modifier.height(MikaSpace.S20))
                BigSearchField(query, { query = it }, onGo = {
                    if (query.isNotBlank()) {
                        val q = query
                        query = ""
                        onSubmit(q)
                    }
                })
                if (query.isBlank()) {
                    EngineNote(engine) { pickingEngine = true }
                    SectionHeader(tr(R.string.browser_favorites), if (sites.isEmpty()) null else tr(R.string.browser_edit)) { editing = true }
                    SiteGrid(sites, face, editing = false, onOpen = onOpenSite, onRemove = {}, onEdit = { editing = true }, onAdd = onAddHint)
                    if (sites.any { it.loggedIn }) Legend(BROWSER_LOGIN_LEGEND, dot = true)
                    SectionHeader(tr(R.string.browser_bookmarks), if (bookmarks.isEmpty()) null else tr(R.string.browser_all), onShowBookmarks)
                    BookmarkCard(bookmarks, face, onOpenBookmark)
                    SectionHeader(tr(R.string.browser_recent_source), if (recent.isEmpty()) null else tr(R.string.browser_all), onHistory)
                    RecentCard(recent, face, now, onOpenUrl)
                } else {
                    val suggestions = remember(query, recent, engine) { StartPageModel.suggestions(query, recent, engine) }
                    SuggestionCard(suggestions, face) { s ->
                        query = ""
                        onOpenUrl(s.url)
                    }
                    Caption(tr(R.string.browser_typing_note, engine.displayName))
                }
            }
            Spacer(Modifier.height(MikaSpace.S24))
        }
        if (pickingEngine) {
            EnginePicker(
                engine, custom,
                onPick = { pickingEngine = false; onPickEngine(it) },
                onAdd = { pickingEngine = false; addingEngine = true },
                onRemove = onRemoveEngine,
                onDismiss = { pickingEngine = false },
            )
        }
        if (addingEngine) {
            CustomEngineDialog(
                onSave = { addingEngine = false; onAddEngine(it) },
                onDismiss = { addingEngine = false },
            )
        }
    }
}

/** The large search box: a white pill 52 high, with an accent ring when focused; the keyboard key is "go". */
@Composable
private fun BigSearchField(value: String, onChange: (String) -> Unit, onGo: () -> Unit) {
    val c = MikaTheme.colors
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).keepsKeyboard().cardSurface(c, MikaShapes.Pill)
            .then(if (focused) Modifier.border(1.5.dp, c.accent, MikaShapes.Pill) else Modifier)
            .padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MikaIcons.Search, null, Modifier.size(20.dp), tint = c.ink3)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(BROWSER_SEARCH_HINT, style = BigText, color = c.ink3, maxLines = 1)
            BasicTextField(
                value, onChange,
                Modifier.fillMaxWidth().testTag("browser-search").onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = BigText.copy(color = c.ink, textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = tr(R.string.browser_clear_input), role = Role.Button) { onChange("") },
                contentAlignment = Alignment.Center,
            ) { Icon(MikaIcons.Close, tr(R.string.browser_clear_input), Modifier.size(16.dp), tint = c.ink3) }
        }
    }
}

private val BigText = MikaType.Body

@Composable
private fun Caption(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(top = 8.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3, textAlign = TextAlign.Center,
    )
}

/** The small line under the search box, "search with Bing": tap it to switch the search engine. */
@Composable
private fun EngineNote(engine: SearchEngineSpec, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Text(
        tr(R.string.browser_engine_note, engine.displayName),
        Modifier.fillMaxWidth().clip(MikaShapes.Pill)
            .clickable(role = Role.Button, onClickLabel = tr(R.string.browser_engine_choose), onClick = onClick)
            .padding(top = 8.dp, bottom = 2.dp).testTag("browser-engine"),
        style = MikaType.Hint, color = c.ink3, textAlign = TextAlign.Center,
    )
}

/** The switch-search-engine dialog: built-in and custom engines laid out as a column of radio rows, dismissed on selection; another can be added below, and custom ones can be removed. */
@Composable
private fun EnginePicker(
    engine: SearchEngineSpec,
    custom: List<CustomSearchEngine>,
    onPick: (SearchEngineSpec) -> Unit,
    onAdd: () -> Unit,
    onRemove: (CustomSearchEngine) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MikaTheme.colors
    val engines: List<SearchEngineSpec> = SearchEngine.entries + custom
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.browser_engine_choose)) },
        text = {
            Column(Modifier.testTag("browser-engine-picker")) {
                engines.forEachIndexed { i, e ->
                    RadioRow(
                        e.displayName, selected = e.id == engine.id, onClick = { onPick(e) }, first = i == 0,
                        trailing = {
                            if (e is CustomSearchEngine) {
                                val remove = tr(R.string.browser_engine_custom_remove, e.displayName)
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape)
                                        .clickable(role = Role.Button, onClickLabel = remove) { onRemove(e) }
                                        .testTag("browser-engine-remove"),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(MikaIcons.Close, remove, Modifier.size(16.dp), tint = c.ink3) }
                            }
                        },
                    )
                }
                TextButton(onClick = onAdd, modifier = Modifier.testTag("browser-engine-add")) {
                    Text(tr(R.string.browser_engine_custom_add))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.done)) } },
    )
}

/** Add a custom search engine: a name and a URL template with %s standing for the search term; when invalid it shows a hint and does not dismiss. */
@Composable
private fun CustomEngineDialog(onSave: (CustomSearchEngine) -> Unit, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.browser_engine_custom_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MikaTextField(
                    name, { name = it; invalid = false }, Modifier.fillMaxWidth().testTag("browser-engine-name"),
                    singleLine = true, placeholder = { Text(tr(R.string.browser_engine_custom_name)) },
                )
                MikaTextField(
                    url, { url = it; invalid = false }, Modifier.fillMaxWidth().testTag("browser-engine-url"),
                    singleLine = true, placeholder = { Text(tr(R.string.browser_engine_custom_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                Text(tr(R.string.browser_engine_custom_hint), style = MikaType.Hint, color = c.ink3)
                if (invalid) Text(tr(R.string.browser_engine_custom_invalid), style = MikaType.Hint, color = c.warn)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val engine = CustomSearchEngine.create(name, url)
                    if (engine == null) invalid = true else onSave(engine)
                },
                modifier = Modifier.testTag("browser-engine-save"),
            ) { Text(tr(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

/** A section header, optionally with a small text link on the right ([action]). */
@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MikaType.Label, color = c.ink3)
        if (action != null) {
            Text(
                action, Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 6.dp, vertical = 2.dp),
                style = MikaType.Label, color = c.accent,
            )
        }
    }
}

@Composable
private fun Legend(text: String, dot: Boolean) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (dot) Box(Modifier.size(9.dp).background(c.ok, CircleShape))
        Text(text, style = MikaType.Hint, color = c.ink3)
    }
}

/** Favorites, four cells per row; when [onAdd] is not null the last cell is "+". */
@Composable
private fun SiteGrid(
    sites: List<FavoriteSite>,
    face: (String) -> SiteFace,
    editing: Boolean,
    onOpen: (FavoriteSite) -> Unit,
    onRemove: (FavoriteSite) -> Unit,
    onEdit: () -> Unit,
    onAdd: (() -> Unit)?,
) {
    val cells: List<FavoriteSite?> = sites + if (onAdd != null) listOf(null) else emptyList()
    Column(Modifier.fillMaxWidth().testTag("browser-sites"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        cells.chunked(COLUMNS).forEachIndexed { row, chunk ->
            Row(Modifier.fillMaxWidth()) {
                chunk.forEachIndexed { col, site ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        if (site == null) AddCell(onAdd!!)
                        else SiteCell(site, face(site.host), editing, (row * COLUMNS + col) % 2 == 0, { onOpen(site) }, { onRemove(site) }, onEdit)
                    }
                }
                repeat(COLUMNS - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SiteCell(
    site: FavoriteSite,
    face: SiteFace,
    editing: Boolean,
    even: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = MikaTheme.colors
    val reduce = MikaTheme.reduceMotion
    val angle = if (editing && !reduce) {
        rememberInfiniteTransition(label = "wiggle").animateFloat(
            -WIGGLE_DEG, WIGGLE_DEG, infiniteRepeatable(tween(WIGGLE_MS), RepeatMode.Reverse), label = "wiggle-angle",
        ).value
    } else 0f
    Column(
        Modifier.graphicsLayer { rotationZ = if (even) angle else -angle }
            .clip(MikaShapes.Chip)
            .combinedClickable(
                onClickLabel = if (editing) null else tr(R.string.browser_open_site, face.name),
                onLongClickLabel = if (editing) null else tr(R.string.browser_edit_favorites),
                onClick = { if (!editing) onOpen() },
                onLongClick = { if (!editing) onEdit() },
            )
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box {
            SiteTile(face, 54.dp, TileShape, InitialLarge)
            if (site.loggedIn) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(15.dp)
                        .background(c.bg, CircleShape).padding(2.5.dp).background(c.ok, CircleShape).testTag("browser-site-logged-in"),
                )
            }
            if (editing) {
                Box(
                    Modifier.align(Alignment.TopStart).offset((-6).dp, (-6).dp).size(22.dp).clip(CircleShape)
                        .background(c.ink2).clickable(onClickLabel = tr(R.string.browser_remove_site, face.name), role = Role.Button, onClick = onRemove)
                        .testTag("browser-site-remove"),
                    contentAlignment = Alignment.Center,
                ) { Icon(MikaIcons.Close, tr(R.string.browser_remove_site, face.name), Modifier.size(11.dp), tint = c.bg) }
            }
        }
        Text(face.name, style = MikaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AddCell(onAdd: () -> Unit) {
    val c = MikaTheme.colors
    Column(
        Modifier.clip(MikaShapes.Chip).clickable(onClickLabel = tr(R.string.browser_add_favorite), role = Role.Button, onClick = onAdd)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(54.dp).cardSurface(c, TileShape), contentAlignment = Alignment.Center) {
            Icon(MikaIcons.Plus, null, Modifier.size(24.dp), tint = c.ink3)
        }
        Text(tr(R.string.add), style = MikaType.Hint, color = c.ink2, maxLines = 1)
    }
}

@Composable
private fun RecentCard(recent: List<RecentItem>, face: (String) -> SiteFace, now: Long, onOpen: (String) -> Unit) {
    val c = MikaTheme.colors
    GroupCard {
        if (recent.isEmpty()) {
            Text(tr(R.string.browser_no_recent), Modifier.fillMaxWidth().padding(16.dp), style = MikaType.Body, color = c.ink3)
        }
        recent.forEachIndexed { i, r ->
            if (i > 0) Hairline(start = 56.dp)
            SmallRow(
                r.title, r.subtitle, onClick = { onOpen(r.url) },
                lead = { if (r.search || r.host == null) SearchMini() else SiteTile(face(r.host), 28.dp, MiniShape, InitialSmall) },
                trailing = { Text(StartPageModel.relativeTime(r.openedAtMs, now), style = MikaType.Hint, color = c.ink3, maxLines = 1) },
            )
        }
    }
}

/** Bookmarks on the start page: at most [MAX_BOOKMARKS] of the most recently added; when there are none, explains how to add one. */
@Composable
private fun BookmarkCard(bookmarks: List<PageBookmark>, face: (String) -> SiteFace, onOpen: (PageBookmark) -> Unit) {
    val c = MikaTheme.colors
    GroupCard {
        if (bookmarks.isEmpty()) {
            Text(tr(R.string.browser_bookmark_empty), Modifier.fillMaxWidth().padding(16.dp), style = MikaType.Body, color = c.ink3)
        }
        bookmarks.take(MAX_BOOKMARKS).forEachIndexed { i, b ->
            if (i > 0) Hairline(start = 56.dp)
            val host = WebUrlPolicy.host(b.url)
            val site = host?.let { face(it).name } ?: b.url
            SmallRow(
                b.title.ifBlank { site }, host ?: b.url, onClick = { onOpen(b) },
                lead = { if (host != null) SiteTile(face(host), 28.dp, MiniShape, InitialSmall) else SearchMini(MikaIcons.Bookmark) },
            )
        }
    }
}

@Composable
private fun SuggestionCard(list: List<Suggestion>, face: (String) -> SiteFace, onPick: (Suggestion) -> Unit) {
    if (list.isEmpty()) return
    GroupCard(Modifier.padding(top = 8.dp).testTag("browser-suggestions")) {
        list.forEachIndexed { i, s ->
            if (i > 0) Hairline(start = 56.dp)
            SmallRow(
                s.title, s.subtitle, onClick = { onPick(s) },
                lead = {
                    when {
                        s.search -> SearchMini()
                        s.host != null -> SiteTile(face(s.host), 28.dp, MiniShape, InitialSmall)
                        else -> SearchMini(MikaIcons.Globe)
                    }
                },
            )
        }
    }
}

/** A row on the start page: a 28 small icon on the left, a title (one line) and small text, with room for a time on the right. */
@Composable
internal fun SmallRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    lead: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        lead()
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(title, style = MikaType.Body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3, maxLines = 1)
        }
        trailing()
    }
}

@Composable
private fun SearchMini(icon: ImageVector = MikaIcons.Search) {
    val c = MikaTheme.colors
    Box(Modifier.size(28.dp).background(c.neutralSoft, MiniShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(16.dp), tint = c.ink2)
    }
}

private val TileShape = MikaShapes.Card
private val InitialLarge = MikaType.Title.copy(fontWeight = FontWeight.Bold)
private const val WIGGLE_DEG = 1.5f
private const val WIGGLE_MS = 250
