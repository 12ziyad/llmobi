package app.llmobi.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.llmobi.data.Catalog
import app.llmobi.data.Chat
import app.llmobi.data.Installed
import app.llmobi.data.Message
import app.llmobi.data.ModelEntry
import app.llmobi.data.ModelSettings
import app.llmobi.data.Store
import app.llmobi.device.DeviceProfile
import app.llmobi.device.DeviceProfiler
import app.llmobi.device.Fit
import app.llmobi.download.ModelDownloadWorker
import app.llmobi.engine.Engines
import app.llmobi.ui.theme.ThemeChoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

enum class Screen { CHAT, STORE, MY_AIS, SETTINGS, APPEARANCE, STORAGE, MODEL_SETTINGS }

class AppState(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val store = Store(ctx)
    private val prefs = ctx.getSharedPreferences("llmobi", Context.MODE_PRIVATE)

    // ---- global ui state
    var screen by mutableStateOf(Screen.CHAT)
        private set
    var drawerOpen by mutableStateOf(false)
    var theme by mutableStateOf(ThemeChoice.valueOf(prefs.getString("theme", "DARK")!!))
        private set
    var wifiOnly by mutableStateOf(prefs.getBoolean("wifi_only", true))
        private set
    var advanced by mutableStateOf(prefs.getBoolean("advanced", false))
        private set

    var device by mutableStateOf(DeviceProfiler.read(ctx))
        private set

    // ---- model + chat state
    var modelId by mutableStateOf(prefs.getString("last_model", null))
        private set
    val model: ModelEntry? get() = modelId?.let { Catalog.byId(it) }

    var installed = mutableStateListOf<Installed>()
        private set
    val chats: SnapshotStateList<Chat> = mutableStateListOf()
    val messages: SnapshotStateList<Message> = mutableStateListOf()

    var chatId by mutableStateOf<Long?>(null)
        private set
    var generating by mutableStateOf(false)
        private set
    var engineNote by mutableStateOf<String?>(null)
        private set
    var toast by mutableStateOf<String?>(null)

    private var genJob: Job? = null
    private var streamingMessageId: Long? = null

    init {
        refresh()
        // If nothing was ever chosen, land on the smallest thing this phone can run.
        if (modelId == null) {
            val best = DeviceProfiler.rank(Catalog.models, device)
                .firstOrNull { it.second == Fit.EXCELLENT }?.first
            modelId = best?.id ?: Catalog.models.first().id
        }
    }

    // ---------------------------------------------------------------- data

    fun refresh() {
        device = DeviceProfiler.read(ctx)
        adoptOrphans()
        installed.clear()
        installed.addAll(store.installed())
        modelId?.let { reloadChats(it) }
    }

    /**
     * Picks up model files that exist on disk but have no database row.
     *
     * This happens if the app is force-killed between the download finishing and
     * the row being written, and it also lets a model be side-loaded during
     * development. Cheap to run and it means a paid-for download is never lost.
     */
    private fun adoptOrphans() {
        val dir = ModelDownloadWorker.modelsDir(ctx)
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.name.endsWith(".gguf") || f.length() < 1_000_000L) continue
            val id = f.name.removeSuffix(".gguf")
            if (Catalog.byId(id) == null) continue
            if (store.isInstalled(id)) continue
            store.markInstalled(id, f.absolutePath, f.length())
        }
    }

    private fun reloadChats(id: String) {
        chats.clear()
        chats.addAll(store.chats(id))
    }

    fun isInstalled(id: String) = installed.any { it.modelId == id }

    fun fitOf(m: ModelEntry): Fit = DeviceProfiler.fit(m, device)

    fun ranked(): List<Pair<ModelEntry, Fit>> = DeviceProfiler.rank(Catalog.models, device)

    fun settingsFor(id: String): ModelSettings = store.settings(id)

    fun saveSettings(s: ModelSettings) = store.saveSettings(s)

    fun chatCount(id: String) = store.chatCount(id)

    fun historyBytes() = store.historyBytes()

    // ---------------------------------------------------------------- nav

    fun go(s: Screen) {
        screen = s
        drawerOpen = false
    }

    fun selectModel(id: String) {
        if (modelId == id) return
        stopGeneration()
        modelId = id
        prefs.edit().putString("last_model", id).apply()
        chatId = null
        messages.clear()
        reloadChats(id)
        engineNote = null
        Engines.release()
        store.touch(id)
        refresh()
    }

    // ---------------------------------------------------------------- chats

    fun newChat() {
        stopGeneration()
        chatId = null
        messages.clear()
        drawerOpen = false
        screen = Screen.CHAT
    }

    fun openChat(id: Long) {
        stopGeneration()
        chatId = id
        messages.clear()
        messages.addAll(store.messages(id))
        drawerOpen = false
        screen = Screen.CHAT
    }

    fun pinChat(id: Long, pinned: Boolean) {
        store.pinChat(id, pinned)
        modelId?.let { reloadChats(it) }
    }

    fun renameChat(id: Long, title: String) {
        store.renameChat(id, title)
        modelId?.let { reloadChats(it) }
    }

    fun deleteChat(id: Long) {
        store.deleteChat(id)
        if (chatId == id) {
            chatId = null
            messages.clear()
        }
        modelId?.let { reloadChats(it) }
    }

    // ---------------------------------------------------------------- send

    fun send(text: String) {
        val m = model ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || generating) return

        val inst = installed.firstOrNull { it.modelId == m.id }
        if (inst == null) {
            toast = "Install ${m.name} first."
            return
        }

        if (chatId == null) {
            chatId = store.newChat(m.id, trimmed.take(48))
            reloadChats(m.id)
        }
        val cid = chatId!!

        val myId = store.addMessage(cid, "me", trimmed)
        messages.add(Message(myId, cid, "me", trimmed, System.currentTimeMillis()))

        val aiId = store.addMessage(cid, "ai", "")
        messages.add(Message(aiId, cid, "ai", "", System.currentTimeMillis()))
        streamingMessageId = aiId

        generating = true
        val s = store.settings(m.id)
        val ctxSize = when (s.memory) {
            "short" -> 1024
            "long" -> minOf(m.ctxDefault * 2, 8192)
            else -> m.ctxDefault
        }

        genJob = viewModelScope.launch {
            val ok = Engines.ensureLoaded(m.id, inst.path, ctxSize)
            if (!ok) {
                finishStream(aiId, "I could not load onto this phone right now. Close some apps and try again.")
                return@launch
            }
            engineNote = if (Engines.usingRealEngine) null else "Preview engine - llama.cpp not built yet"

            val prompt = buildPrompt(s.system, trimmed)
            val sb = StringBuilder()
            try {
                Engines.engine().generate(prompt, 512, s.creativity).collectLatest { tok ->
                    sb.append(tok)
                    val idx = messages.indexOfFirst { it.id == aiId }
                    if (idx >= 0) messages[idx] = messages[idx].copy(text = sb.toString())
                }
            } catch (_: Throwable) {
                // Falls through to whatever was produced before the failure.
            }
            finishStream(aiId, sb.toString().ifBlank { "(no reply)" })
        }
    }

    private fun buildPrompt(system: String, user: String): String {
        val sys = system.ifBlank { "You are a helpful assistant running locally on the user's phone." }
        val history = messages
            .dropLast(1)
            .takeLast(10)
            .joinToString("\n") { if (it.role == "me") "User: ${it.text}" else "Assistant: ${it.text}" }
        return buildString {
            append(sys).append("\n\n")
            if (history.isNotBlank()) append(history).append("\n")
            append("User: ").append(user).append("\nAssistant:")
        }
    }

    private fun finishStream(aiId: Long, finalText: String) {
        store.updateMessage(aiId, finalText)
        val idx = messages.indexOfFirst { it.id == aiId }
        if (idx >= 0) messages[idx] = messages[idx].copy(text = finalText)
        generating = false
        streamingMessageId = null
        modelId?.let { reloadChats(it) }
    }

    fun stopGeneration() {
        if (!generating) return
        Engines.engine().stop()
        genJob?.cancel()
        genJob = null
        val id = streamingMessageId
        if (id != null) {
            val idx = messages.indexOfFirst { it.id == id }
            val partial = if (idx >= 0) messages[idx].text else ""
            store.updateMessage(id, partial.ifBlank { "(stopped)" })
            if (idx >= 0 && partial.isBlank()) messages[idx] = messages[idx].copy(text = "(stopped)")
        }
        generating = false
        streamingMessageId = null
    }

    // ---------------------------------------------------------------- install

    fun install(m: ModelEntry) {
        ModelDownloadWorker.start(ctx, m.id, wifiOnly)
        toast = "Installing ${m.name}"
    }

    fun cancelInstall(m: ModelEntry) {
        ModelDownloadWorker.cancel(ctx, m.id)
    }

    fun uninstall(id: String, alsoChats: Boolean) {
        if (Engines.currentModelId == id) Engines.release()
        ModelDownloadWorker.fileFor(ctx, id).delete()
        File(ModelDownloadWorker.fileFor(ctx, id).absolutePath + ".part").delete()
        store.uninstall(id, alsoChats)
        app.llmobi.shortcut.Shortcuts.remove(ctx, id)
        refresh()
        toast = "Removed"
    }

    // ---------------------------------------------------------------- prefs

    fun chooseTheme(t: ThemeChoice) {
        theme = t
        prefs.edit().putString("theme", t.name).apply()
    }

    fun chooseWifiOnly(v: Boolean) {
        wifiOnly = v
        prefs.edit().putBoolean("wifi_only", v).apply()
    }

    fun chooseAdvanced(v: Boolean) {
        advanced = v
        prefs.edit().putBoolean("advanced", v).apply()
    }

    override fun onCleared() {
        stopGeneration()
        super.onCleared()
    }
}
