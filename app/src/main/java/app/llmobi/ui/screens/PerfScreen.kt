package app.llmobi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.llmobi.engine.Engines
import app.llmobi.perf.Perf
import app.llmobi.ui.AppState
import app.llmobi.ui.Screen
import app.llmobi.ui.components.BigButton
import app.llmobi.ui.components.Card
import app.llmobi.ui.components.Mono
import app.llmobi.ui.components.SectionLabel
import app.llmobi.ui.theme.LocalSkin
import kotlin.math.roundToInt

/**
 * The performance readout.
 *
 * Deliberately honest: on a phone this slow the numbers are not flattering, and
 * hiding them would just make people think the app is broken rather than that
 * the hardware is working hard. The plain-language verdict at the top is for
 * everyone; the table underneath is for people who want the detail.
 */
@Composable
fun PerfScreen(s: AppState) {
    val skin = LocalSkin.current
    val avg = Perf.avgTokensPerSec
    val runs = Perf.runs

    Column(Modifier.fillMaxSize().background(skin.bg).statusBarsPadding()) {
        TopBar("Speed", onBack = { s.go(Screen.SETTINGS) }, trailing = "${runs.size} REPLIES")

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            // ---- headline
            item {
                Card {
                    Column {
                        Mono("HOW FAST THIS PHONE IS", size = 9)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                if (avg > 0) "%.1f".format(avg) else "—",
                                color = skin.red,
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Black,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.padding(bottom = 9.dp)) {
                                Mono("WORDS PER SECOND", size = 10)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            Perf.verdict(avg),
                            color = skin.fg,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (Perf.avgFirstWordMs > 0) {
                            Spacer(Modifier.height(8.dp))
                            Mono(
                                "TYPICAL WAIT BEFORE THE FIRST WORD: ${Perf.avgFirstWordMs} MS",
                                size = 9,
                            )
                        }
                    }
                }
            }

            // ---- what limits it
            item {
                SectionLabel("Why it runs at this speed")
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Line("Chip", s.device.soc)
                        Line("Fast cores", "2 at 2.2 GHz")
                        Line("Slow cores", "6 at 2.0 GHz")
                        Line("Threads used", "6")
                        Line("Free memory", "${s.device.availableRamMb} MB of ${s.device.totalRamMb} MB")
                        Line("Running on", if (Engines.usingRealEngine) "CPU (no GPU offload)" else "preview stub")
                    }
                }
            }

            item {
                Card {
                    Text(
                        "Every word is real maths happening on your phone's own processor, " +
                            "with no server involved. A phone chip is roughly a hundred times " +
                            "slower at this than the datacentre hardware behind a cloud chatbot, " +
                            "so a few words a second is the honest ceiling here. Smaller models " +
                            "are faster; bigger ones are slower.",
                        color = skin.grey,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
            }

            // ---- recent replies
            if (runs.isNotEmpty()) {
                item { SectionLabel("Recent replies") }

                item {
                    Card {
                        Column {
                            Row(Modifier.fillMaxWidth()) {
                                Mono("MODEL", size = 8, modifier = Modifier.weight(1.4f))
                                Mono("WAIT", size = 8, modifier = Modifier.weight(1f))
                                Mono("WORDS", size = 8, modifier = Modifier.weight(0.9f))
                                Mono("W/SEC", size = 8, modifier = Modifier.weight(0.9f))
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(skin.line))
                            Spacer(Modifier.height(4.dp))

                            runs.take(12).forEach { r ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        r.modelName,
                                        color = skin.fg,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1.4f),
                                        maxLines = 1,
                                    )
                                    Mono(
                                        if (r.firstWordMs >= 1000) "${"%.1f".format(r.firstWordMs / 1000.0)}s"
                                        else "${r.firstWordMs}ms",
                                        color = if (r.firstWordMs > 3000) skin.amber else skin.grey,
                                        size = 11, spacing = 0.2,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Mono(
                                        r.tokens.toString(),
                                        color = skin.grey, size = 11, spacing = 0.2,
                                        modifier = Modifier.weight(0.9f),
                                    )
                                    Mono(
                                        "%.1f".format(r.tokensPerSec),
                                        color = if (r.tokensPerSec >= 12) skin.green else skin.amber,
                                        size = 11, spacing = 0.2,
                                        weight = FontWeight.Bold,
                                        modifier = Modifier.weight(0.9f),
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- simple bar chart, most recent on the right
                item {
                    SectionLabel("Speed over the last replies")
                    Card {
                        val recent = runs.take(14).reversed()
                        val peak = (recent.maxOfOrNull { it.tokensPerSec } ?: 1.0).coerceAtLeast(1.0)
                        Column {
                            Row(
                                Modifier.fillMaxWidth().height(86.dp),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                recent.forEach { r ->
                                    val frac = (r.tokensPerSec / peak).toFloat().coerceIn(0.06f, 1f)
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .fillMaxHeight(frac)
                                            .background(if (r.tokensPerSec >= 12) skin.green else skin.red)
                                    )
                                }
                            }
                            Spacer(Modifier.height(7.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Mono("OLDEST", size = 8, modifier = Modifier.weight(1f))
                                Mono("PEAK ${peak.roundToInt()} W/S", size = 8)
                                Spacer(Modifier.weight(1f))
                                Mono("NEWEST", size = 8)
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    BigButton("Clear measurements", ghost = true) { Perf.clear() }
                }
            } else {
                item {
                    Card {
                        Mono("SEND A MESSAGE AND THE NUMBERS APPEAR HERE", size = 10)
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }
}

@Composable
private fun Line(k: String, v: String) {
    val skin = LocalSkin.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(k, color = skin.grey2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Mono(v, color = skin.fg, size = 11, spacing = 0.2)
    }
}
