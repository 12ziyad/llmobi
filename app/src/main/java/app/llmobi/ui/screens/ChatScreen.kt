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
            // A quiet download indicator where every platform puts one: top
            // right, a ring filling up, gone the moment the file lands.
            val dl = s.activeDownloads.entries.firstOrNull()
            if (dl != null) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clickable { s.drawerOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { (dl.value / 100f).coerceIn(0.02f, 1f) },
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.5.dp,
                        color = skin.red,
                        trackColor = skin.bg3,
                    )
                    Mono("${dl.value}", color = skin.fg, size = 7, spacing = 0.0)
                }
            }
            IconBtn("+") { s.newChat() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.line))

        // ---------------- messages
        val listState = rememberLazyListState()
        // Animate only when a whole new message appears.
        LaunchedEffect(s.messages.size) {
            if (s.messages.isNotEmpty()) listState.animateScrollToItem(s.messages.size - 1)
        }
        // While tokens stream in, pin to the bottom cheaply. Keying this on the
        // growing text length started a fresh animation ~20 times a second, and
        // each one cancelled the last.
        LaunchedEffect(s.generating) {
            while (s.generating) {
                if (s.messages.isNotEmpty()) listState.scrollToItem(s.messages.size - 1)
                kotlinx.coroutines.delay(120)
            }
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
            app.llmobi.ui.components.BigButton("Install · ${model.sizeLabel}") { s.askInstall(model) }
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
                    if (m.text.isBlank() && s.loadingModel) {
                        Column {
                            Text(
                                "Waking " + (s.model?.name ?: "the model") + " up",
                                color = skin.fg,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Mono("READING THE MODEL OFF STORAGE · A FEW SECONDS", size = 8)
                        }
                    } else if (m.text.isBlank()) {
                        Column {
                            Text(
                                "Thinking",
                                color = skin.fg,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Mono("YOUR PHONE IS DOING THE MATHS ITSELF", size = 8)
                        }
                    } else {
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
