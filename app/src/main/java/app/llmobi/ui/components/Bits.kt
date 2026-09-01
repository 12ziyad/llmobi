package app.llmobi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.llmobi.data.ModelEntry
import app.llmobi.device.Fit
import app.llmobi.ui.theme.LocalSkin

/** Monospace micro-label. Used everywhere for anything that reads as data. */
@Composable
fun Mono(
    text: String,
    color: Color? = null,
    size: Int = 10,
    weight: FontWeight = FontWeight.Medium,
    spacing: Double = 1.4,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    Text(
        text = text,
        color = color ?: skin.grey2,
        fontSize = size.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = weight,
        letterSpacing = spacing.sp,
        modifier = modifier,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Mono(text.uppercase(), size = 9, modifier = modifier.padding(start = 2.dp, top = 10.dp, bottom = 6.dp))
}

/** The squared-off card the whole app is built from. No rounded-everything. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val skin = LocalSkin.current
    Box(
        modifier
            .fillMaxWidth()
            .background(skin.bg2)
            .border(BorderStroke(1.dp, skin.line))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) { content() }
}

@Composable
fun ModelIcon(model: ModelEntry, size: Int = 40) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.28f).dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(model.colorStart), Color(model.colorEnd))
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            model.iconLetter,
            color = Color(0xFF101214),
            fontSize = (size * 0.38f).sp,
            fontWeight = FontWeight.Black,
        )
    }
}

/**
 * States what a model needs, not whether we think it will work.
 *
 * The verdict version of this ("WON'T RUN") was wrong to show in a list: free
 * memory moves around, closing apps changes the answer, and telling somebody
 * their phone is inadequate over and over is a miserable way to browse. The
 * requirements sheet on install is where the honest warning belongs.
 */
@Composable
fun NeedChip(minRamMb: Int, comfortable: Boolean) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (comfortable) skin.green.copy(alpha = 0.14f) else skin.bg3)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Mono(
            "NEEDS %.1f GB".format(minRamMb / 1024.0),
            color = if (comfortable) skin.green else skin.grey2,
            size = 9, weight = FontWeight.Bold, spacing = 0.5,
        )
    }
}

@Composable
fun FitChip(fit: Fit) {
    val skin = LocalSkin.current
    val (bg, fg, label) = when (fit) {
        Fit.EXCELLENT -> Triple(skin.green.copy(alpha = 0.16f), skin.green, "EXCELLENT")
        Fit.RECOMMENDED -> Triple(skin.green.copy(alpha = 0.16f), skin.green, "RECOMMENDED")
        Fit.HEAVY -> Triple(skin.amber.copy(alpha = 0.16f), skin.amber, "HEAVY")
        Fit.WONT_RUN -> Triple(skin.red.copy(alpha = 0.15f), skin.red, "WON'T RUN")
        Fit.NO_SPACE -> Triple(skin.red.copy(alpha = 0.15f), skin.red, "NO SPACE")
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) { Mono(label, color = fg, size = 9, weight = FontWeight.Bold, spacing = 0.6) }
}

@Composable
fun BigButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    ghost: Boolean = false,
    onClick: () -> Unit,
) {
    val skin = LocalSkin.current
    val bg = when {
        ghost -> Color.Transparent
        danger -> Color.Transparent
        !enabled -> skin.bg3
        else -> skin.red
    }
    val fg = when {
        danger -> skin.red
        ghost -> skin.grey
        !enabled -> skin.grey2
        else -> skin.onRed
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(bg)
            .then(
                if (ghost || danger) Modifier.border(BorderStroke(1.dp, if (danger) skin.red else skin.line))
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.2.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun Bar(fraction: Float, hot: Boolean = false, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(skin.bg3)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(if (hot) skin.red else skin.fg)
        )
    }
}

@Composable
fun SettingRow(
    title: String,
    sub: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val skin = LocalSkin.current
    Card(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = skin.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (sub != null) {
                    Box(Modifier.padding(top = 3.dp)) { Mono(sub.uppercase(), size = 9) }
                }
            }
            if (value != null) Mono(value, color = skin.grey, size = 11, spacing = 0.6)
            trailing?.invoke()
        }
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (on) skin.red else skin.bg3)
            .clickable { onChange(!on) },
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (on) Color.White else skin.grey2)
        )
    }
}

@Composable
fun Chip(text: String, sub: String? = null, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Box(
        modifier
            .background(if (selected) skin.red.copy(alpha = 0.12f) else skin.bg2)
            .border(BorderStroke(1.dp, if (selected) skin.red else skin.line))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                text.uppercase(),
                color = if (selected) skin.fg else skin.grey,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
            if (sub != null) {
                Box(Modifier.padding(top = 2.dp)) {
                    Mono(sub.uppercase(), color = if (selected) skin.red else skin.grey2, size = 8, spacing = 0.6)
                }
            }
        }
    }
}
