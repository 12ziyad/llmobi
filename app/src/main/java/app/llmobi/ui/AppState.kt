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
import app.llmobi.data.CatalogSync
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
import app.llmobi.engine.Turn
import app.llmobi.perf.Perf
import app.llmobi.perf.Run
import app.llmobi.safety.Safety
import app.llmobi.ui.theme.ThemeChoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { CHAT, STORE, MY_AIS, SETTINGS, APPEARANCE, STORAGE, MODEL_SETTINGS, PERFORMANCE }

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

    /**
     * The catalog actually in use. Starts as whatever is cached on disk (or the
     * bundled list), then swaps to the freshly fetched one if a refresh lands.
     */
    var catalog by mutableStateOf(CatalogSync.load(ctx))
        private set

    // ---- model + chat state
    var modelId by mutableStateOf(prefs.getString("last_model", null))
        private set
    val model: ModelEntry? get() = modelId?.let { id -> catalog.firstOrNull { it.id == id } ?: Catalog.byId(id) }

    var installed = mutableStateListOf<Installed>()
        private set
    val chats: SnapshotStateList<Chat> = mutableStateListOf()
    val messages: SnapshotStateList<Message> = mutableStateListOf()

    var chatId by mutableStateOf<Long?>(null)
        private set
    var generating by mutableStateOf(false)
        private set

    /** True while the model is being read off disk - seconds on a cold start. */
    var loadingModel by mutableStateOf(false)
        private set
    var engineNote by mutableStateOf<String?>(null)
        private set
    var toast by mutableStateOf<String?>(null)

    /** Model awaiting the user's yes on the requirements sheet. */
    var pendingInstall by mutableStateOf<ModelEntry?>(null)
        private set

    private var genJob: Job? = null
    private var streamingMessageId: Long? = null

    init {
        refresh()
        // Fire and forget: the store is already usable from the cached list, so
        // a slow or absent network only ever means slightly stale entries.
        viewModelScope.launch {
            if (CatalogSync.refresh(ctx)) {
                catalog = CatalogSync.load(ctx)
                refresh()
            }
        }
        // If nothing was ever chosen, land on the smallest thing this phone can run.
        if (modelId == null) {
            val best = DeviceProfiler.rank(catalog, device)
                .firstOrNull { it.second == Fit.EXCELLENT }?.first
            modelId = best?.id ?: catalog.first().id
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

    fun ranked(): List<Pair<ModelEntry, Fit>> = DeviceProfiler.rank(catalog, device)

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
            val sentAt = System.currentTimeMillis()
            Perf.startLive()

            // Loading reads hundreds of megabytes off flash. It happens on the
            // engine thread, but the user still needs to see that we are busy.
            val alreadyUp = Engines.currentModelId == m.id && Engines.engine().loaded

            // Everything below re-checks reality at the moment of loading rather
            // than trusting what the store decided earlier: free memory moves,
            // downloads get truncated, and this may be a phone we have never seen.
            if (!alreadyUp) {
                val file = java.io.File(inst.path)
                if (!Safety.looksLikeGguf(file)) {
                    Perf.endLive()
                    finishStream(
                        aiId,
                        "That model file looks incomplete. Delete ${m.name} from the menu " +
                            "and install it again - the download was probably interrupted.",
                    )
                    return@launch
                }
                if (Safety.isBlocked(ctx, m.id)) {
                    Perf.endLive()
                    finishStream(
                        aiId,
                        "${m.name} has crashed this phone twice while loading, so I have " +
                            "stopped trying. A smaller AI will work better here. You can " +
                            "force it from Settings if you want to try anyway.",
                    )
                    return@launch
                }
            }

            device = DeviceProfiler.read(ctx)
            val usableMb = device.availableRamMb + (device.totalRamMb - device.availableRamMb) / 5
            val plan = Safety.planLoad(usableMb, m.minRamMb, ctxSize)
            if (!plan.ok) {
                Perf.endLive()
                finishStream(aiId, plan.reason ?: "Not enough free memory right now.")
                return@launch
            }
            plan.reason?.let { engineNote = it }

            loadingModel = !alreadyUp
            val loadStart = System.currentTimeMillis()
            if (!alreadyUp) Safety.beginLoad(ctx, m.id)
            val ok = Engines.ensureLoaded(m.id, inst.path, plan.contextSize)
            if (!alreadyUp) Safety.endLoad(ctx, m.id, ok)
            val loadMs = if (loadingModel) System.currentTimeMillis() - loadStart else 0L
            loadingModel = false
            if (!ok) {
                Perf.endLive()
                finishStream(
                    aiId,
                    "${m.name} would not load on this phone. Close some other apps and try " +
                        "again, or pick a smaller AI from the menu.",
                )
                return@launch
            }
            engineNote = if (Engines.usingRealEngine) null else "Preview engine - llama.cpp not built yet"

            val turns = withContext(Dispatchers.Default) { buildTurns(s.system, trimmed) }
            val sb = StringBuilder()
            var lastPush = 0L
            var tokens = 0
            var firstWordAt = 0L
            try {
                // collect, never collectLatest: collectLatest cancels the previous
                // token's handler when the next arrives, which silently drops text.
                Engines.engine().generate(turns, 400, s.creativity).collect { tok ->
                    if (firstWordAt == 0L) firstWordAt = System.currentTimeMillis()
                    tokens++
                    sb.append(tok)
                    // Pushing every token recomposes the list ~20x a second for no
                    // visible gain. Coalescing to ~60 ms reads identically and costs
                    // a fraction of the frame time.
                    val now = System.currentTimeMillis()
                    if (now - lastPush >= 60L) {
                        lastPush = now
                        val idx = messages.indexOfFirst { it.id == aiId }
                        if (idx >= 0) messages[idx] = messages[idx].copy(text = sb.toString())
                        Perf.tickLive(tokens, firstWordAt - sentAt)
                    }
                }
            } catch (_: Throwable) {
                // Falls through to whatever was produced before the failure.
            }
            val endedAt = System.currentTimeMillis()
            Perf.endLive()
            if (tokens > 0) {
                Perf.record(
                    Run(
                        modelId = m.id,
                        modelName = m.name,
                        loadMs = loadMs,
                        firstWordMs = (firstWordAt - sentAt).coerceAtLeast(0),
                        genMs = (endedAt - firstWordAt).coerceAtLeast(1),
                        tokens = tokens,
                        promptTurns = turns.size,
                        at = sentAt,
                    )
                )
            }
            finishStream(aiId, sb.toString().ifBlank { "(no reply)" })
        }
    }

    /**
     * The conversation as discrete turns, for the model's own chat template.
     *
     * The last two entries are dropped: they are the message just added and the
     * empty assistant bubble being streamed into. History is capped at eight turns
     * because every extra turn is prompt the phone must re-read before it can say
     * a single word.
     */
    private fun buildTurns(system: String, user: String): List<Turn> {
        val out = ArrayList<Turn>(12)
        // Naming the model matters more than it looks. Asked "what are you", a
        // small model with no identity in its prompt confabulates one from
        // whatever assistant text dominated its training data - ours cheerfully
        // claimed to be Claude, made by Anthropic.
        //
        // State it positively and never name another company. An earlier version
        // said "you were not made by OpenAI, Anthropic or Google" and the model
        // simply echoed one of them back: at this size, a negation mostly just
        // raises the probability of the word being negated.
        val name = model?.name ?: "an AI assistant"
        out += Turn(
            "system",
            system.ifBlank {
                "You are $name, a small AI model running entirely on the user's " +
                    "phone, offline. If you are asked who or what you are, say you are " +
                    "$name running locally on this device. Answer directly and keep it " +
                    "short unless asked for detail."
            },
        )
        messages
            .dropLast(2)
            .takeLast(8)
            .filter { it.text.isNotBlank() }
            .forEach { out += Turn(if (it.role == "me") "user" else "assistant", it.text) }
        out += Turn("user", user)
        return out
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
        loadingModel = false
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

    /** Opens the requirements sheet. Nothing downloads until they confirm. */
    fun askInstall(m: ModelEntry) {
        device = DeviceProfiler.read(ctx)
        pendingInstall = m
        drawerOpen = false
    }

    fun dismissInstall() {
        pendingInstall = null
    }

    fun confirmInstall() {
        val m = pendingInstall ?: return
        pendingInstall = null
        ModelDownloadWorker.start(ctx, m.id, wifiOnly)
        toast = "Installing ${m.name} - keep going in the background"
    }

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
