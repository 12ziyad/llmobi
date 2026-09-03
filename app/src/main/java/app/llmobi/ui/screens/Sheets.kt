package app.llmobi.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.llmobi.data.ModelEntry
import app.llmobi.device.DeviceProfile
import app.llmobi.ui.components.BigButton
import app.llmobi.ui.components.Card
import app.llmobi.ui.components.ModelIcon
import app.llmobi.ui.components.Mono
import app.llmobi.ui.theme.LocalSkin

/** Dimmed backdrop that swallows taps, shared by both sheets. */
@Composable
private fun Scrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6060708))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Shown before any install. Says plainly what the model needs and what the phone
 * has, then lets the person decide.
 *
 * Deliberately never blocks. Telling somebody "this will not work on your phone"
 * and removing the button is both patronising and sometimes wrong - free memory
 * moves around, and closing a few apps can change the answer. Show the numbers,
 * give an honest warning, and leave the choice with them.
 */
@Composable
fun InstallSheet(
    model: ModelEntry,
    device: DeviceProfile,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
) {
    val skin = LocalSkin.current

    val needRamMb = model.minRamMb
    val haveRamMb = device.availableRamMb + (device.totalRamMb - device.availableRamMb) / 5
    val ramOk = haveRamMb >= needRamMb

    val needStoreMb = model.fileBytes / 1_048_576L
    val haveStoreMb = device.freeStorageMb
    val storeOk = haveStoreMb >= needStoreMb + 1024

    val allGood = ramOk && storeOk

    Scrim(onCancel) {
        Column(
            Modifier
                .padding(22.dp)
                .background(skin.bg2)
                .border(BorderStroke(1.dp, skin.line))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ModelIcon(model, 52)
            Spacer(Modifier.height(13.dp))
            Text(
                "Install ${model.name}?".uppercase(),
                color = skin.fg, fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                model.tagline,
                color = skin.grey2, fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(18.dp))
            Requirement(
                label = "Download size",
                need = model.sizeLabel,
                have = "%.0f GB free".format(haveStoreMb / 1024.0),
                ok = storeOk,
            )
            Spacer(Modifier.height(6.dp))
            Requirement(
                label = "Memory to run it",
                need = "%.1f GB".format(needRamMb / 1024.0),
                have = "%.1f GB usable".format(haveRamMb / 1024.0),
                ok = ramOk,
            )

            Spacer(Modifier.height(16.dp))
            if (allGood) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✓", color = skin.green, fontSize = 15.sp)
                        Spacer(Modifier.width(9.dp))
                        Text(
                            "Looks like it fits. Speed depends on your phone.",
                            color = skin.grey, fontSize = 12.5.sp,
                        )
                    }
                }
            } else {
                // A warning, not a verdict. The memory figure is an estimate and
                // phones under-report what they can free, so "won't run" was
                // wrong often enough to be dishonest. Say what we know and let
                // them try.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background((if (!storeOk) skin.red else skin.amber).copy(alpha = 0.10f))
                        .border(BorderStroke(1.dp, if (!storeOk) skin.red else skin.amber))
                        .padding(13.dp)
                ) {
                    Mono(
                        if (!storeOk) "NOT ENOUGH STORAGE" else "NOT SURE THIS WILL RUN WELL",
                        color = if (!storeOk) skin.red else skin.amber, size = 9, weight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        if (!storeOk)
                            "Free up some space first, or the download will not finish."
                        else
                            "Your phone has less free memory than this model usually wants. " +
                                "It might still work - many do. If it is very slow or stops, " +
                                "close other apps, or a smaller AI will fly.",
                        color = skin.grey, fontSize = 12.5.sp, lineHeight = 18.sp,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            BigButton(
                if (allGood || !storeOk) "Install · ${model.sizeLabel}" else "Try it anyway · ${model.sizeLabel}",
                danger = !allGood,
                onClick = onInstall,
            )
            Spacer(Modifier.height(7.dp))
            BigButton("Not now", ghost = true, onClick = onCancel)

            Spacer(Modifier.height(12.dp))
            Mono("DOWNLOADS ONCE · THEN WORKS OFFLINE FOREVER", size = 8)
        }
    }
}

@Composable
private fun Requirement(label: String, need: String, have: String, ok: Boolean) {
    val skin = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(skin.bg3)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = skin.fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Mono(have.uppercase(), size = 8)
        }
        Column(horizontalAlignment = Alignment.End) {
            Mono(
                need.uppercase(),
                color = if (ok) skin.green else skin.red,
                size = 11, weight = FontWeight.Bold, spacing = 0.4,
            )
            Spacer(Modifier.height(3.dp))
            Mono(if (ok) "FITS" else "TIGHT", color = if (ok) skin.green else skin.red, size = 8)
        }
    }
}

/**
 * Deleting gigabytes should never feel risky: say exactly what is freed, keep the
 * conversations by default, and make clear it is one tap to get back.
 */
@Composable
fun DeleteSheet(
    model: ModelEntry,
    chats: Int,
    onCancel: () -> Unit,
    onDelete: (keepChats: Boolean) -> Unit,
) {
    val skin = LocalSkin.current
    var keep by remember { mutableStateOf(true) }

    Scrim(onCancel) {
        Column(
            Modifier
                .padding(22.dp)
                .background(skin.bg2)
                .border(BorderStroke(1.dp, skin.line))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ModelIcon(model, 52)
            Spacer(Modifier.height(13.dp))
            Text(
                "Delete ${model.name}?".uppercase(),
                color = skin.fg, fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Mono("FREES ${model.sizeLabel.uppercase()} OF STORAGE", size = 10)
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
                    app.llmobi.ui.components.Toggle(keep) { keep = it }
                }
            }

            Spacer(Modifier.height(16.dp))
            BigButton("Delete") { onDelete(keep) }
            Spacer(Modifier.height(7.dp))
            BigButton("Cancel", ghost = true, onClick = onCancel)
            Spacer(Modifier.height(12.dp))
            Mono("YOU CAN REINSTALL IT ANY TIME, FREE", size = 8)
        }
    }
}
