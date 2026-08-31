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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.llmobi.data.Message
import app.llmobi.ui.AppState
import app.llmobi.ui.Screen
import app.llmobi.ui.components.Card
import app.llmobi.ui.components.Mono
import app.llmobi.ui.components.ModelIcon
import app.llmobi.ui.theme.LocalSkin

@Composable
fun ChatScreen(s: AppState) {
    val skin = LocalSkin.current
    val model = s.model

    Column(
        Modifier
            .fillMaxSize()
            .background(skin.bg)
            .statusBarsPadding()
            .imePadding()
    ) {
        // ---------------- top bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(skin.bg)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn("☰") { s.drawerOpen = true }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    (model?.name ?: "LLMobi").uppercase(),
                    color = skin.fg,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.height(2.dp))
                Mono(
                    if (s.engineNote != null) "● PREVIEW ENGINE" else "● RUNNING ON THIS PHONE",
                    color = if (s.engineNote != null) skin.amber else skin.green,
                    size = 8,
                    spacing = 1.2,
                )
            }
            IconBtn("+") { s.newChat() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.line))

        // ---------------- messages
        val listState = rememberLazyListState()
        LaunchedEffect(s.messages.size, s.messages.lastOrNull()?.text?.length) {
            if (s.messages.isNotEmpty()) listState.animateScrollToItem(s.messages.size - 1)
        }

        if (s.messages.isEmpty()) {
            Welcome(s, Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(s.messages, key = { it.id }) { m -> Bubble(m, s) }
            }
        }

        // ---------------- composer
        Composer(s)
    }
}

@Composable
private fun IconBtn(glyph: String, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = skin.grey, fontSize = 19.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Welcome(s: AppState, modifier: Modifier) {
    val skin = LocalSkin.current
    val model = s.model ?: return
    val installed = s.isInstalled(model.id)

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ModelIcon(model, 56)
        Spacer(Modifier.height(14.dp))
        Text(
            if (installed) "${model.name} is ready".uppercase() else "${model.name} not installed".uppercase(),
            color = skin.fg,
            fontSize = 21.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (installed) "Ask anything. It runs on your phone, so it works with no internet."
            else "Install it once and it works offline forever.",
            color = skin.grey2,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        if (!installed) {
            app.llmobi.ui.components.BigButton("Install · ${model.sizeLabel}") { s.install(model) }
        } else {
            val ideas = listOf(
                "How much does 1kg of ice weigh?",
                "Explain photosynthesis simply",
                "Why is my python loop stuck?",
            )
            ideas.forEach { q ->
                Card(onClick = { s.send(q) }) {
                    Text(q, color = skin.grey, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun Bubble(m: Message, s: AppState) {
    val skin = LocalSkin.current
    if (m.role == "me") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .fillMaxWidth(0.86f)
                    .background(skin.bg3)
                    .padding(horizontal = 13.dp, vertical = 10.dp)
            ) {
                Text(m.text, color = skin.fg, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    } else {
        Column {
            Mono("${(s.model?.name ?: "AI").uppercase()} · ON DEVICE", size = 8, spacing = 1.2)
            Spacer(Modifier.height(5.dp))
            Row {
                Box(Modifier.width(2.dp).height(1.dp))
                Box(
                    Modifier
                        .background(skin.red)
                        .width(2.dp)
                        .heightIn(min = 20.dp)
                ) {}
                Box(Modifier.padding(start = 11.dp)) {
                    Text(
                        m.text.ifBlank { "█" },
                        color = skin.fg.copy(alpha = 0.92f),
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(s: AppState) {
    val skin = LocalSkin.current
    var text by remember { mutableStateOf("") }
    val ready = text.isNotBlank()

    Column(
        Modifier
            .fillMaxWidth()
            .background(skin.bg)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 9.dp, bottom = 12.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.line))
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(skin.bg2)
                .border(BorderStroke(1.dp, skin.line))
                .padding(start = 12.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f).padding(bottom = 8.dp, top = 4.dp)) {
                if (text.isEmpty()) {
                    Text("Ask anything…", color = skin.grey3, fontSize = 14.sp)
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = LocalTextStyle.current.copy(color = skin.fg, fontSize = 14.sp, lineHeight = 20.sp),
                    cursorBrush = SolidColor(skin.red),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 110.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .background(if (s.generating || ready) skin.red else skin.bg3)
                    .clickable(enabled = s.generating || ready) {
                        if (s.generating) {
                            s.stopGeneration()
                        } else {
                            s.send(text)
                            text = ""
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (s.generating) "■" else "↑",
                    color = if (s.generating || ready) skin.onRed else skin.grey2,
                    fontSize = if (s.generating) 15.sp else 19.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Mono("NO INTERNET NEEDED  ·  NOTHING LEAVES THIS PHONE", color = skin.grey3, size = 8, spacing = 0.8)
        }
    }
}

// ---------------------------------------------------------------- drawer

@Composable
fun Drawer(s: AppState) {
    val skin = LocalSkin.current
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(skin.bg2)
            .statusBarsPadding()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "LLMOBI",
                color = skin.fg,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(11.dp))
            app.llmobi.ui.components.BigButton("+ New chat") { s.newChat() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.line))

        val pinned = s.chats.filter { it.pinned }
        val recent = s.chats.filter { !it.pinned }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            if (pinned.isNotEmpty()) {
                item { app.llmobi.ui.components.SectionLabel("Pinned") }
                items(pinned, key = { it.id }) { c ->
                    ChatRow(
                        title = c.title, pinned = true, active = s.chatId == c.id,
                        menuOpen = menuFor == c.id,
                        renaming = renaming == c.id, renameText = renameText,
                        onRenameChange = { renameText = it },
                        onRenameDone = { s.renameChat(c.id, renameText); renaming = null },
                        onClick = { s.openChat(c.id) },
                        onMenu = { menuFor = if (menuFor == c.id) null else c.id },
                        onPin = { s.pinChat(c.id, false); menuFor = null },
                        onRename = { renaming = c.id; renameText = c.title; menuFor = null },
                        onDelete = { s.deleteChat(c.id); menuFor = null },
                    )
                }
            }
            item { app.llmobi.ui.components.SectionLabel("Recent") }
            if (recent.isEmpty()) {
                item {
                    Box(Modifier.padding(8.dp)) { Mono("NO CHATS YET", size = 10) }
                }
            }
            items(recent, key = { it.id }) { c ->
                ChatRow(
                    title = c.title, pinned = false, active = s.chatId == c.id,
                    menuOpen = menuFor == c.id,
                    renaming = renaming == c.id, renameText = renameText,
                    onRenameChange = { renameText = it },
                    onRenameDone = { s.renameChat(c.id, renameText); renaming = null },
                    onClick = { s.openChat(c.id) },
                    onMenu = { menuFor = if (menuFor == c.id) null else c.id },
                    onPin = { s.pinChat(c.id, true); menuFor = null },
                    onRename = { renaming = c.id; renameText = c.title; menuFor = null },
                    onDelete = { s.deleteChat(c.id); menuFor = null },
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.line))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).navigationBarsPadding()) {
            s.model?.let { m ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { s.go(Screen.MY_AIS) }
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModelIcon(m, 28)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.name.uppercase(), color = skin.fg, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                        )
                        Spacer(Modifier.height(2.dp))
                        Mono("${m.sizeLabel} · TAP TO SWITCH", size = 8)
                    }
                    Text("▾", color = skin.grey3, fontSize = 12.sp)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { s.go(Screen.SETTINGS) }
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚙", color = skin.grey2, fontSize = 15.sp)
                Spacer(Modifier.width(10.dp))
                Text("Settings", color = skin.grey2, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ChatRow(
    title: String,
    pinned: Boolean,
    active: Boolean,
    menuOpen: Boolean,
    renaming: Boolean,
    renameText: String,
    onRenameChange: (String) -> Unit,
    onRenameDone: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
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
                    value = renameText,
                    onValueChange = onRenameChange,
                    singleLine = true,
                    textStyle = TextStyle(color = skin.fg, fontSize = 13.sp),
                    cursorBrush = SolidColor(skin.red),
                    modifier = Modifier
                        .weight(1f)
                        .border(BorderStroke(1.dp, skin.red))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clickable(onClick = onRenameDone).padding(6.dp)) {
                    Mono("SAVE", color = skin.red, size = 9)
                }
            } else {
                Text(
                    title,
                    color = if (active) skin.fg else skin.grey,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onMenu),
                    contentAlignment = Alignment.Center,
                ) { Text("⋮", color = skin.grey3, fontSize = 14.sp) }
            }
        }
        if (menuOpen) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, bottom = 4.dp)
                    .background(skin.bg3)
                    .border(BorderStroke(1.dp, skin.line))
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
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, color = if (danger) skin.red else skin.grey, fontSize = 13.sp)
    }
}
