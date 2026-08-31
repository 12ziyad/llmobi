package app.llmobi.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.llmobi.ui.screens.AppearanceScreen
import app.llmobi.ui.screens.ChatScreen
import app.llmobi.ui.screens.Drawer
import app.llmobi.ui.screens.ModelSettingsScreen
import app.llmobi.ui.screens.MyAisScreen
import app.llmobi.ui.screens.SettingsScreen
import app.llmobi.ui.screens.StorageScreen
import app.llmobi.ui.screens.StoreScreen
import app.llmobi.ui.theme.LLMobiTheme
import app.llmobi.ui.theme.LocalSkin
import kotlinx.coroutines.delay

@Composable
fun Root(s: AppState) {
    LLMobiTheme(s.theme) {
        val skin = LocalSkin.current

        Box(Modifier.fillMaxSize().background(skin.bg)) {

            when (s.screen) {
                Screen.CHAT -> ChatScreen(s)
                Screen.STORE -> StoreScreen(s)
                Screen.MY_AIS -> MyAisScreen(s)
                Screen.SETTINGS -> SettingsScreen(s)
                Screen.APPEARANCE -> AppearanceScreen(s)
                Screen.STORAGE -> StorageScreen(s)
                Screen.MODEL_SETTINGS -> ModelSettingsScreen(s)
            }

            // ---- sliding drawer
            val open = s.drawerOpen
            val dim by animateFloatAsState(if (open) 0.72f else 0f, tween(200), label = "dim")
            val slide by animateFloatAsState(if (open) 0f else -1f, tween(220), label = "slide")

            if (dim > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF060708).copy(alpha = dim))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { s.drawerOpen = false }
                )
            }
            if (slide > -0.999f) {
                val screenW = LocalConfiguration.current.screenWidthDp.dp
                val drawerW = if (screenW * 0.82f > 320.dp) 320.dp else screenW * 0.82f
                val px = with(LocalDensity.current) { drawerW.toPx() }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(drawerW)
                        .graphicsLayer { translationX = slide * px }
                ) { Drawer(s) }
            }

            // ---- transient message
            val msg = s.toast
            if (msg != null) {
                LaunchedEffect(msg) {
                    delay(2200)
                    s.toast = null
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp, start = 22.dp, end = 22.dp)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(skin.bg3)
                            .padding(horizontal = 14.dp, vertical = 11.dp)
                    ) {
                        Text(msg, color = skin.fg, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
