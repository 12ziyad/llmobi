package app.llmobi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import app.llmobi.data.Catalog
import app.llmobi.ui.AppState
import app.llmobi.ui.Root

/**
 * The entry point every home-screen icon uses.
 *
 * A pinned shortcut fires llmobi://chat/model/<id>. We read the id, point the
 * app at that model, and drop straight into its chat - no store, no picker, no
 * "load model" step. That single hop is the whole illusion of separate apps.
 */
class ChatActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state: AppState = viewModel()
            androidx.compose.runtime.LaunchedEffect(Unit) {
                modelIdFrom(intent)?.let { state.selectModel(it) }
            }
            Root(state)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        modelIdFrom(intent)?.let { androidx.lifecycle.ViewModelProvider(this)[AppState::class.java].selectModel(it) }
    }

    private fun modelIdFrom(intent: Intent?): String? {
        val fromExtra = intent?.getStringExtra("model_id")
        if (fromExtra != null && app.llmobi.data.CatalogSync.find(this, fromExtra) != null) return fromExtra

        // llmobi://chat/model/<id>
        val uri = intent?.data ?: return null
        if (uri.scheme != "llmobi" || uri.host != "chat") return null
        val segs = uri.pathSegments
        val id = segs.lastOrNull() ?: return null
        return if (app.llmobi.data.CatalogSync.find(this, id) != null) id else null
    }
}
