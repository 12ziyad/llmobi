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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.llmobi.data.Catalog
import app.llmobi.data.ModelEntry
import app.llmobi.device.Fit
import app.llmobi.shortcut.Shortcuts
import app.llmobi.ui.AppState
import app.llmobi.ui.Screen
import app.llmobi.ui.components.Bar
import app.llmobi.ui.components.BigButton
import app.llmobi.ui.components.Card
import app.llmobi.ui.components.Chip
import app.llmobi.ui.components.FitChip
import app.llmobi.ui.components.ModelIcon
import app.llmobi.ui.components.Mono
import app.llmobi.ui.components.SectionLabel
import app.llmobi.ui.components.SettingRow
import app.llmobi.ui.components.Toggle
import app.llmobi.ui.theme.LocalSkin
import app.llmobi.ui.theme.ThemeChoice

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, trailing: String? = null) {
    val skin = LocalSkin.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(skin.bg)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Text("←", color = skin.grey, fontSize = 19.sp) }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                title.uppercase(),
                color = skin.fg,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) Mono(trailing, size = 9)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.line))
    }
}

// ---------------------------------------------------------------- store

@Composable
fun StoreScreen(s: AppState) {
    val skin = LocalSkin.current
    val ranked = remember(s.device, s.installed.size) { s.ranked() }

    Column(Modifier.fillMaxSize().background(skin.bg).statusBarsPadding()) {
        TopBar("Store", onBack = { s.go(Screen.CHAT) }, trailing = "${s.device.totalRamMb / 1024} GB PHONE")
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            item { SectionLabel("Best for your phone") }
            items(ranked, key = { it.first.id }) { (m, fit) ->
                ModelRow(m, fit, s)
            }
            item { Spacer(Modifier.height(20.dp).navigationBarsPadding()) }
        }
    }
}

@Composable
private fun ModelRow(m: ModelEntry, fit: Fit, s: AppState) {
    val skin = LocalSkin.current
    val installed = s.isInstalled(m.id)
    Card(onClick = { s.selectModel(m.id); s.go(Screen.CHAT) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModelIcon(m, 40)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    m.name.uppercase(), color = skin.fg, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(m.tagline, color = skin.grey2, fontSize = 11.5.sp, maxLines = 1)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono(m.sizeLabel, size = 10, spacing = 0.4)
                    Spacer(Modifier.width(7.dp))
                    FitChip(fit)
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        when {
                            installed -> skin.green.copy(alpha = 0.16f)
                            fit == Fit.WONT_RUN || fit == Fit.NO_SPACE -> Color.Transparent
                            else -> skin.red
                        }
                    )
                    .clickable {
                        if (installed) { s.selectModel(m.id); s.go(Screen.CHAT) } else s.install(m)
                    }
                    .padding(horizontal = 13.dp, vertical = 7.dp)
            ) {
                Mono(
                    when {
                        installed -> "OPEN"
                        fit == Fit.WONT_RUN || fit == Fit.NO_SPACE -> "VIEW"
                        else -> "INSTALL"
                    },
                    color = when {
                        installed -> skin.green
                        fit == Fit.WONT_RUN || fit == Fit.NO_SPACE -> skin.grey2
                        else -> skin.onRed
                    },
                    size = 10, weight = FontWeight.Bold, spacing = 0.8,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- my ais

@Composable
fun MyAisScreen(s: AppState) {
    val skin = LocalSkin.current
    val usedMb = s.installed.sumOf { it.bytes } / 1_048_576L

    Column(Modifier.fillMaxSize().background(skin.bg).statusBarsPadding()) {
        TopBar("My AIs", onBack = { s.go(Screen.CHAT) }, trailing = "${s.installed.size} INSTALLED")
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            item {
                Card {
                    Column {
                        Row(Modifier.fillMaxWidth()) {
                            Mono("PHONE STORAGE", size = 9, modifier = Modifier.weight(1f))
                            Mono(
                                "%.1f / %d GB".format(usedMb / 1024.0, s.device.totalStorageMb / 1024),
                                size = 9,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Bar(
                            fraction = if (s.device.totalStorageMb > 0)
                                usedMb.toFloat() / s.device.totalStorageMb.toFloat() else 0f,
                            hot = true,
                        )
                    }
                }
            }
            if (s.installed.isEmpty()) {
                item {
                    Box(Modifier.padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Mono("NOTHING INSTALLED YET", size = 10)
                    }
                }
            }
            items(s.installed, key = { it.modelId }) { inst ->
                val m = Catalog.byId(inst.modelId) ?: return@items
                Card(onClick = { s.selectModel(m.id); s.go(Screen.CHAT) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModelIcon(m, 36)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.name.uppercase(), color = skin.fg, fontSize = 15.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                            )
                            Spacer(Modifier.height(3.dp))
                            Mono(
                                "${"%.1f".format(inst.bytes / 1_073_741_824.0)} GB · ${s.chatCount(m.id)} CHATS",
                                size = 9,
                            )
                        }
                        Box(
                            Modifier.clickable { s.selectModel(m.id); s.go(Screen.MODEL_SETTINGS) }
                                .padding(8.dp)
                        ) { Text("⋮", color = skin.grey3, fontSize = 15.sp) }
                    }
                }
            }
            item {
                Spacer(Modifier.height(6.dp))
                BigButton("+ Browse more AIs", ghost = true) { s.go(Screen.STORE) }
                Spacer(Modifier.height(20.dp).navigationBarsPadding())
            }
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(s: AppState) {
    val skin = LocalSkin.current
    val usedGb = "%.1f".format(s.installed.sumOf { it.bytes } / 1_073_741_824.0)

    Column(Modifier.fillMaxSize().background(skin.bg).statusBarsPadding()) {
        TopBar("Settings", onBack = { s.go(Screen.CHAT) })
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            item {
                SettingRow(
                    "Appearance",
                    sub = "${s.theme.name} · red",
                    value = "›",
                    onClick = { s.go(Screen.APPEARANCE) },
                )
            }
            item {
                SettingRow("Download on Wi-Fi only", sub = "saves your mobile data") {
                    Toggle(s.wifiOnly) { s.chooseWifiOnly(it) }
                }
            }
            item {
                SettingRow("Storage", sub = "$usedGb GB used", value = "›", onClick = { s.go(Screen.STORAGE) })
            }
            item { SectionLabel("Privacy") }
            item {
                Card {
                    Column {
                        Mono("YOUR CHATS NEVER LEAVE THIS PHONE.", color = skin.red, size = 10, spacing = 0.6)
                        Spacer(Modifier.height(4.dp))
                        Mono("THERE IS NO SERVER TO SEND THEM TO.", color = skin.grey2, size = 10, spacing = 0.6)
                    }
                }
            }
            item { SectionLabel("For power users") }
            item {
                SettingRow("Advanced mode", sub = "quantization, threads, import gguf") {
                    Toggle(s.advanced) { s.chooseAdvanced(it) }
                }
            }
            if (s.advanced) {
                item {
                    Card {
                        Column {
                            Mono("DEVICE", size = 9)
                            Spacer(Modifier.height(6.dp))
                            listOf(
                                "SOC" to s.device.soc,
                                "ABI" to s.device.abi,
                                "CORES" to s.device.cores.toString(),
                                "RAM TOTAL" to "${s.device.totalRamMb} MB",
                                "RAM FREE" to "${s.device.availableRamMb} MB",
                                "STORAGE FREE" to "${s.device.freeStorageMb / 1024} GB",
                                "ENGINE" to if (app.llmobi.engine.Engines.usingRealEngine) "llama.cpp" else "preview stub",
                            ).forEach { (k, v) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Mono(k, size = 9, modifier = Modifier.weight(1f))
                                    Mono(v, color = skin.grey, size = 9, spacing = 0.4)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(10.dp))
                SettingRow("About", sub = "version 1.0.0 · open source")
                Spacer(Modifier.height(20.dp).navigationBarsPadding())
            }
        }
    }
}

@Composable
fun AppearanceScreen(s: AppState) {
    val skin = LocalSkin.current
    Column(Modifier.fillMaxSize().background(skin.bg).statusBarsPadding()) {
        TopBar("Appearance", onBack = { s.go(Screen.SETTINGS) })
        Column(Modifier.padding(12.dp)) {
            SectionLabel("Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Dark", "default", s.theme == ThemeChoice.DARK, Modifier.weight(1f)) {
                    s.chooseTheme(ThemeChoice.DARK)
                }
                Chip("Light", null, s.theme == ThemeChoice.LIGHT, Modifier.weight(1f)) {
                    s.chooseTheme(ThemeChoice.LIGHT)
                }
                Chip("Auto", "match phone", s.theme == ThemeChoice.AUTO, Modifier.weight(1f)) {
                    s.chooseTheme(ThemeChoice.AUTO)
                }
            }
            Spacer(Modifier.height(6.dp))
            SectionLabel("Accent colour")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(0xFFE5342A, 0xFFE8963C, 0xFF4FCB94, 0xFF5B8DEF, 0xFFB47BE8).forEachIndexed { i, c ->
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(Color(c))
                            .then(
                                if (i == 0) Modifier.border(
                                    BorderStroke(2.dp, skin.fg)
                                ) else Modifier
                            )
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Mono("MORE ACCENTS ARRIVE IN A LATER UPDATE", size = 9)
        }
    }
}

@Composable
fun StorageScreen(s: AppState) {
    val skin = LocalSkin.current
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(skin.bg).statusBarsPadding()) {
        TopBar("Storage", onBack = { s.go(Screen.SETTINGS) }, trailing = "${s.device.freeStorageMb / 1024} GB FREE")
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            item { SectionLabel("Installed") }
            if (s.installed.isEmpty()) {
                item { Box(Modifier.padding(16.dp)) { Mono("NOTHING INSTALLED", size = 10) } }
            }
            items(s.installed, key = { it.modelId }) { inst ->
                val m = Catalog.byId(inst.modelId) ?: return@items
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModelIcon(m, 32)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.name.uppercase(), color = skin.fg, fontSize = 14.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                            )
                            Spacer(Modifier.height(3.dp))
                            Mono("${"%.1f".format(inst.bytes / 1_073_741_824.0)} GB", size = 9)
                        }
                        Box(
                            Modifier.clickable { confirmDelete = m.id }.padding(8.dp)
                        ) { Mono("DELETE", color = skin.red, size = 9, weight = FontWeight.Bold) }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                SettingRow(
                    "Chat history",
                    sub = "all models",
                    value = "${s.historyBytes() / 1024} KB",
                )
                Spacer(Modifier.height(20.dp).navigationBarsPadding())
            }
        }
    }

    confirmDelete?.let { id ->
        val m = Catalog.byId(id) ?: return@let
        DeleteSheet(
            model = m,
            chats = s.chatCount(id),
            onCancel = { confirmDelete = null },
            onDelete = { keepChats -> s.uninstall(id, !keepChats); confirmDelete = null },
        )
    }
}

@Composable
private fun DeleteSheet(
    model: ModelEntry,
    chats: Int,
    onCancel: () -> Unit,
    onDelete: (keepChats: Boolean) -> Unit,
) {
    val skin = LocalSkin.current
    var keep by remember { mutableStateOf(true) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC060708))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .background(skin.bg2)
                .border(BorderStroke(1.dp, skin.line))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ModelIcon(model, 52)
            Spacer(Modifier.height(14.dp))
            Text(
                "Delete ${model.name}?".uppercase(),
                color = skin.fg, fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.height(10.dp))
            Mono("FREES ${model.sizeLabel} OF STORAGE", size = 10)
            Spacer(Modifier.height(3.dp))
            Mono("REMOVES THE HOME SCREEN ICON", size = 10)
            Spacer(Modifier.height(16.dp))
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep my $chats chats", color = skin.fg, fontSize = 13.sp)
                        Spacer(Modifier.height(2.dp))
                        Mono("SO REINSTALLING RESTORES THEM", size = 8)
                    }
                    Toggle(keep) { keep = it }
                }
            }
            Spacer(Modifier.height(16.dp))
            BigButton("Delete") { onDelete(keep) }
            Spacer(Modifier.height(7.dp))
            BigButton("Cancel", ghost = true) { onCancel() }
        }
    }
}

// ---------------------------------------------------------------- model settings

@Composable
fun ModelSettingsScreen(s: AppState) {
    val skin = LocalSkin.current
    val m = s.model ?: return
    var cfg by remember(m.id) { mutableStateOf(s.settingsFor(m.id)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(Modifier.fillMaxSize().background(skin.bg).statusBarsPadding()) {
        TopBar(m.name, onBack = { s.go(Screen.MY_AIS) })
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModelIcon(m, 40)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.name.uppercase(), color = skin.fg, fontSize = 15.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                            )
                            Spacer(Modifier.height(3.dp))
                            Mono(m.tagline.uppercase().take(34), size = 8)
                        }
                    }
                }
            }
            item { SectionLabel("Creativity") }
            item {
                Card {
                    Column {
                        Slider(
                            value = cfg.creativity,
                            onValueChange = { cfg = cfg.copy(creativity = it); s.saveSettings(cfg) },
                            valueRange = 0.1f..1.4f,
                            colors = SliderDefaults.colors(
                                thumbColor = skin.fg,
                                activeTrackColor = skin.red,
                                inactiveTrackColor = skin.bg3,
                            ),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Mono("PRECISE", size = 8, modifier = Modifier.weight(1f))
                            Mono("CREATIVE", size = 8)
                        }
                    }
                }
            }
            item { SectionLabel("Memory") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Short", "less ram", cfg.memory == "short", Modifier.weight(1f)) {
                        cfg = cfg.copy(memory = "short"); s.saveSettings(cfg)
                    }
                    Chip("Normal", "~30 msgs", cfg.memory == "normal", Modifier.weight(1f)) {
                        cfg = cfg.copy(memory = "normal"); s.saveSettings(cfg)
                    }
                    Chip("Long", "more ram", cfg.memory == "long", Modifier.weight(1f)) {
                        cfg = cfg.copy(memory = "long"); s.saveSettings(cfg)
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                BigButton("Add to home screen", ghost = true) {
                    if (Shortcuts.requestPin(ctx, m)) s.toast = "Check your home screen"
                    else s.toast = "Your launcher does not support this"
                }
            }
            item {
                Spacer(Modifier.height(6.dp))
                BigButton("Delete · frees ${m.sizeLabel}", danger = true) { confirmDelete = true }
                Spacer(Modifier.height(20.dp).navigationBarsPadding())
            }
        }
    }

    if (confirmDelete) {
        DeleteSheet(
            model = m,
            chats = s.chatCount(m.id),
            onCancel = { confirmDelete = false },
            onDelete = { keepChats ->
                s.uninstall(m.id, !keepChats)
                confirmDelete = false
                s.go(Screen.MY_AIS)
            },
        )
    }
}
