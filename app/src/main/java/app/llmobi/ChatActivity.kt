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
            modelIdFrom(intent)?.let { state.selectModel(it) }
            Root(state)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun modelIdFrom(intent: Intent?): String? {
        val fromExtra = intent?.getStringExtra("model_id")
        if (fromExtra != null && Catalog.byId(fromExtra) != null) return fromExtra

        // llmobi://chat/model/<id>
        val segs = intent?.data?.pathSegments ?: return null
        val id = segs.lastOrNull() ?: return null
        return if (Catalog.byId(id) != null) id else null
    }
}
