package app.llmobi.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.llmobi.data.ModelEntry
import app.llmobi.device.Fit
import app.llmobi.ui.AppState
import app.llmobi.ui.Screen
import app.llmobi.ui.components.BigButton
import app.llmobi.ui.components.NeedChip
import app.llmobi.ui.components.ModelIcon
import app.llmobi.ui.components.Mono
import app.llmobi.ui.components.SectionLabel
import app.llmobi.ui.theme.LocalSkin
import app.llmobi.ui.theme.ThemeChoice

/**
 * The drawer, which is the app's actual navigation.
 *
 * It carries three things a person needs constantly: start a new chat, switch
 * between the AIs they have installed, and browse for more. Everything that used
 * to be buried three taps deep - the theme especially - is reachable from here.
 */
@Composable
fun Drawer(s: AppState) {
    val skin = LocalSkin.current
    var tab by remember { mutableStateOf(DrawerTab.CHATS) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(skin.bg2)
            .statusBarsPadding()
    ) {
        // ---------------- header
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "LLMOBI",
                    color = skin.fg,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f),
                )
                ThemeToggle(s)
            }
            Spacer(Modifier.height(11.dp))
            BigButton("+ New chat") { s.newChat() }
        }

        // ---------------- tabs
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            DrawerTab.entries.forEach { t ->
                val on = tab == t
                Box(
                    Modifier
                        .weight(1f)
                        .clickable { tab = t }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Mono(
                            t.label,
                            color = if (on) skin.fg else skin.grey2,
                            size = 10,
                            weight = if (on) FontWeight.Bold else FontWeight.Medium,
                        )
                        Spacer(Modifier.height(7.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(if (on) skin.red else Color.Transparent)
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.line))

        when (tab) {
            DrawerTab.CHATS -> ChatList(
                s, Modifier.weight(1f),
                menuFor, { menuFor = it },
                renaming, { renaming = it },
                renameText, { renameText = it },
            )
            DrawerTab.MY_AIS -> MyAiList(s, Modifier.weight(1f))
            DrawerTab.BROWSE -> BrowseList(s, Modifier.weight(1f))
        }

        // ---------------- footer
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.line))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { s.go(Screen.SETTINGS) }
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚙", color = skin.grey2, fontSize = 15.sp)
            Spacer(Modifier.width(10.dp))
            Text("Settings", color = skin.grey2, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Mono("SPEED · STORAGE", size = 8)
        }
    }
}

enum class DrawerTab(val label: String) {
    CHATS("CHATS"), MY_AIS("MY AIS"), BROWSE("BROWSE")
}

/** Three-way theme switch, right in the header where it can actually be found. */
@Composable
private fun ThemeToggle(s: AppState) {
    val skin = LocalSkin.current
    Row(
        Modifier.border(BorderStroke(1.dp, skin.line)),
    ) {
        listOf(
            ThemeChoice.DARK to "☾",
            ThemeChoice.LIGHT to "☀",
            ThemeChoice.AUTO to "A",
        ).forEach { (choice, glyph) ->
            val on = s.theme == choice
            Box(
                Modifier
                    .size(width = 30.dp, height = 28.dp)
                    .background(if (on) skin.red else Color.Transparent)
                    .clickable { s.chooseTheme(choice) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    glyph,
                    color = if (on) skin.onRed else skin.grey2,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- chats

@Composable
private fun ChatList(
    s: AppState,
    modifier: Modifier,
    menuFor: Long?,
    setMenu: (Long?) -> Unit,
    renaming: Long?,
    setRenaming: (Long?) -> Unit,
    renameText: String,
    setRenameText: (String) -> Unit,
) {
    val pinned = s.chats.filter { it.pinned }
    val recent = s.chats.filter { !it.pinned }

    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        if (pinned.isNotEmpty()) {
            item { SectionLabel("Pinned") }
            items(pinned, key = { it.id }) { c ->
                ChatRow(
                    c.title, true, s.chatId == c.id, menuFor == c.id,
                    renaming == c.id, renameText, setRenameText,
                    { s.renameChat(c.id, renameText); setRenaming(null) },
                    { s.openChat(c.id) },
                    { setMenu(if (menuFor == c.id) null else c.id) },
                    { s.pinChat(c.id, false); setMenu(null) },
                    { setRenaming(c.id); setRenameText(c.title); setMenu(null) },
                    { s.deleteChat(c.id); setMenu(null) },
                )
            }
        }
        item { SectionLabel("Recent") }
        if (recent.isEmpty()) {
            item { Box(Modifier.padding(8.dp)) { Mono("NO CHATS YET", size = 10) } }
        }
        items(recent, key = { it.id }) { c ->
            ChatRow(
                c.title, false, s.chatId == c.id, menuFor == c.id,
                renaming == c.id, renameText, setRenameText,
                { s.renameChat(c.id, renameText); setRenaming(null) },
                { s.openChat(c.id) },
                { setMenu(if (menuFor == c.id) null else c.id) },
                { s.pinChat(c.id, true); setMenu(null) },
                { setRenaming(c.id); setRenameText(c.title); setMenu(null) },
                { s.deleteChat(c.id); setMenu(null) },
            )
        }
    }
}

@Composable
private fun ChatRow(
    title: String, pinned: Boolean, active: Boolean, menuOpen: Boolean,
    renaming: Boolean, renameText: String, onRenameChange: (String) -> Unit,
    onRenameDone: () -> Unit, onClick: () -> Unit, onMenu: () -> Unit,
    onPin: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
) {
    val skin = LocalSkin.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (active) skin.bg3 else Color.Transparent)
                .clickable(enabled = !renaming, onClick = onClick)
                .padding(start = 9.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pinned) {
                Text("●", color = skin.red, fontSize = 9.sp)
                Spacer(Modifier.width(6.dp))
            }
            if (renaming) {
                BasicTextField(
                    value = renameText, onValueChange = onRenameChange, singleLine = true,
                    textStyle = TextStyle(color = skin.fg, fontSize = 13.sp),
                    cursorBrush = SolidColor(skin.red),
                    modifier = Modifier.weight(1f).border(BorderStroke(1.dp, skin.red))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clickable(onClick = onRenameDone).padding(6.dp)) {
                    Mono("SAVE", color = skin.red, size = 9)
                }
            } else {
                Text(
                    title, color = if (active) skin.fg else skin.grey, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onMenu),
                    contentAlignment = Alignment.Center,
                ) { Text("⋮", color = skin.grey3, fontSize = 14.sp) }
            }
        }
        if (menuOpen) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 4.dp)
                    .background(skin.bg3).border(BorderStroke(1.dp, skin.line))
            ) {
                MenuItem(if (pinned) "Unpin" else "Pin to top", false, onPin)
                MenuItem("Rename", false, onRename)
                MenuItem("Delete", true, onDelete)
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, danger: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Box(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, color = if (danger) skin.red else skin.grey, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------- my ais

/** Installed models: tap to switch to one, or free the space it takes. */
@Composable
private fun MyAiList(s: AppState, modifier: Modifier) {
    val skin = LocalSkin.current
    var confirm by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (s.installed.isEmpty()) {
            item {
                Column(Modifier.padding(12.dp)) {
                    Mono("NOTHING INSTALLED YET", size = 10)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Open Browse and install one. It downloads once, then works with no internet.",
                        color = skin.grey2, fontSize = 12.5.sp,
                    )
                }
            }
        }
        items(s.installed, key = { it.modelId }) { inst ->
            val m = s.catalog.firstOrNull { it.id == inst.modelId } ?: return@items
            val active = s.modelId == m.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (active) skin.bg3 else Color.Transparent)
                    .clickable { s.selectModel(m.id); s.newChat() }
                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelIcon(m, 30)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        m.name.uppercase(), color = if (active) skin.fg else skin.grey,
                        fontSize = 13.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Mono(
                        "%.1f GB · %d CHATS".format(inst.bytes / 1_073_741_824.0, s.chatCount(m.id)),
                        size = 8,
                    )
                }
                if (active) Mono("IN USE", color = skin.green, size = 8, weight = FontWeight.Bold)
                Box(
                    Modifier.clickable { confirm = m.id }.padding(8.dp)
                ) { Mono("DELETE", color = skin.red, size = 8, weight = FontWeight.Bold) }
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(horizontal = 4.dp)) {
                Mono(
                    "TOTAL %.1f GB · %d GB FREE ON PHONE".format(
                        s.installed.sumOf { it.bytes } / 1_073_741_824.0,
                        s.device.freeStorageMb / 1024,
                    ),
                    size = 8,
                )
            }
        }
    }

    confirm?.let { id ->
        val m = s.catalog.firstOrNull { it.id == id } ?: return@let
        DeleteSheet(m, s.chatCount(id), { confirm = null }) { keep ->
            s.uninstall(id, !keep); confirm = null
        }
    }
}

// ---------------------------------------------------------------- browse

/** The whole catalog, sorted so what runs best on this phone comes first. */
@Composable
private fun BrowseList(s: AppState, modifier: Modifier) {
    val skin = LocalSkin.current
    val ranked = remember(s.device, s.installed.size, s.catalog) { s.ranked() }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SectionLabel("Best for your phone first") }
        items(ranked, key = { it.first.id }) { (m, fit) ->
            val installed = s.isInstalled(m.id)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (installed) { s.selectModel(m.id); s.newChat() } else s.askInstall(m)
                    }
                    .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelIcon(m, 30)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        m.name.uppercase(), color = skin.fg, fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Mono(m.sizeLabel, size = 8)
                        Spacer(Modifier.width(6.dp))
                        NeedChip(m.minRamMb, fit == Fit.EXCELLENT || fit == Fit.RECOMMENDED)
                    }
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (installed) skin.green.copy(alpha = 0.16f) else skin.red)
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    Mono(
                        if (installed) "OPEN" else "INSTALL",
                        color = if (installed) skin.green else skin.onRed,
                        size = 9, weight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
