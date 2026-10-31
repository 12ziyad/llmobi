package app.llmobi.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import app.llmobi.update.AppUpdates
import app.llmobi.update.AppUpdateWorker
import app.llmobi.update.UpdateInfo
import app.llmobi.update.RefreshWorker
import app.llmobi.ui.theme.ThemeChoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
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
    var theme by mutableStateOf(runCatching { ThemeChoice.valueOf(prefs.getString("theme", "DARK")!!) }.getOrDefault(ThemeChoice.DARK))
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
    fun findModel(id: String): ModelEntry? = rememberedModels.firstOrNull { it.id == id && isInstalled(id) }
        ?: catalog.firstOrNull { it.id == id } ?: rememberedModels.firstOrNull { it.id == id } ?: Catalog.byId(id)
    private var rememberedModels = CatalogSync.remembered(ctx)
    val model: ModelEntry? get() = modelId?.let { findModel(it) }

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

    /** modelId -> percent, for every download currently running. Drives the top-bar ring. */
    val activeDownloads = mutableStateMapOf<String, Int>()

    var availableUpdate by mutableStateOf(AppUpdates.cached(ctx))
        private set
    var updateProgress by mutableStateOf<Int?>(null)
        private set
    var updateReady by mutableStateOf(false)
        private set
    var updateMessage by mutableStateOf<String?>(null)
        private set
    private var refreshJob: Job? = null
    private val observedDownloads = mutableSetOf<String>()
    val downloadErrors = mutableStateMapOf<String, String>()
    private var genJob: Job? = null
    private var streamingMessageId: Long? = null

    init {
        refresh()
        RefreshWorker.schedule(ctx)
        viewModelScope.launch {
            WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(RefreshWorker.NOW).collect { infos ->
                if (infos.any { it.state == WorkInfo.State.SUCCEEDED }) reloadOnlineData()
            }
        }
        viewModelScope.launch {
            WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(AppUpdateWorker.NAME).collect { infos ->
                val info = AppUpdateWorker.currentInfo(ctx, infos)
                updateProgress = if (info != null && !info.state.isFinished) info.progress.getInt("percent", 0) else null
                if (info?.state == WorkInfo.State.FAILED) updateMessage = info.outputData.getString("error")
                availableUpdate?.let { updateReady = AppUpdates.isReady(ctx, it) }
            }
        }
        // A download may already be running from a previous launch - reattach to
        // every model so its completion still lands in the UI.
        catalog.forEach { observeDownload(it.id) }
        onForeground()
        // If nothing was ever chosen, land on the smallest thing this phone can run.
        if (modelId == null) {
            val best = DeviceProfiler.rank(catalog, device)
                .firstOrNull { it.second == Fit.EXCELLENT }?.first
            modelId = best?.id ?: catalog.first().id
        }
    }

    private fun reloadOnlineData() {
        catalog = CatalogSync.load(ctx)
        catalog.forEach { observeDownload(it.id) }
        availableUpdate = AppUpdates.cached(ctx)
        updateReady = availableUpdate?.let { AppUpdates.isReady(ctx, it) } ?: false
        refresh()
    }

    fun onForeground(force: Boolean = false) {
        reloadOnlineData()
        RefreshWorker.request(ctx, force)
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                CatalogSync.refresh(ctx, force)
                AppUpdates.check(ctx, force)
                if (force) updateMessage = "Checked for updates."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { if (force) updateMessage = "Could not check online. Your installed models still work offline." }
            finally { reloadOnlineData() }
        }
    }

    fun downloadUpdate() {
        val info = availableUpdate ?: return
        updateMessage = null
        updateProgress = 0
        viewModelScope.launch {
            try { AppUpdateWorker.start(ctx, info) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { updateProgress = null; updateMessage = "Could not start the update. Try again." }
        }
    }

    fun cancelUpdate() {
        WorkManager.getInstance(ctx).cancelUniqueWork(AppUpdateWorker.NAME)
        updateProgress = null
        updateMessage = "Update download stopped. Tap Download to resume."
    }

    fun installUpdate() {
        val info = availableUpdate ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { AppUpdates.verifyPackage(ctx, info, AppUpdates.file(ctx, info)) }
                if (!AppUpdates.openInstaller(ctx, info))
                    updateMessage = "Allow updates from LLMobi in Android settings, return here, then tap Install update."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { updateReady = false; updateMessage = e.message ?: "Could not open the Android installer." }
        }
    }

    // ---------------------------------------------------------------- data

    fun refresh() {
        rememberedModels = CatalogSync.remembered(ctx)
        device = DeviceProfiler.read(ctx)
        adoptOrphans()
        installed.clear()
        for (entry in store.installed()) {
            val file = File(entry.path)
            if (file.exists() && file.length() == entry.bytes && Safety.looksLikeGguf(file)) installed.add(entry)
            else store.uninstall(entry.modelId, false) // Restored history can outlive the model weights.
        }
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
            val entry = findModel(id) ?: continue
            if (f.length() != entry.fileBytes || !Safety.looksLikeGguf(f)) continue
            if (store.isInstalled(id)) continue
            store.markInstalled(id, f.absolutePath, f.length())
        }
    }

    private fun reloadChats(id: String) {
        chats.clear()
        chats.addAll(store.chats(id))
    }

    fun isInstalled(id: String) = installed.any { it.modelId == id }

    fun fitOf(m: ModelEntry): Fit = DeviceProfiler.fit(m, device, isInstalled(m.id))

    fun ranked(): List<Pair<ModelEntry, Fit>> = DeviceProfiler.rank(catalog, device, installed.map { it.modelId }.toSet())

    fun settingsFor(id: String): ModelSettings = store.settings(id)

    fun saveSettings(s: ModelSettings) = store.saveSettings(s)

    fun chatCount(id: String) = store.chatCount(id)

    fun historyBytes() = store.historyBytes()

    // ---------------------------------------------------------------- nav

    fun go(s: Screen) {
        if (s == Screen.STORE || s == Screen.STORAGE) refresh()
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
        app.llmobi.engine.LlamaBridge.unavailableReason?.let {
            toast = it
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
            try {
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
            val usableMb = device.usableRamMb
            val plan = if (alreadyUp) Safety.Plan(true, ctxSize, null) else Safety.planLoad(usableMb, m.minRamMb, ctxSize)
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
            var stalled = false
            // A phone that is genuinely out of memory does not fail - it pages
            // the weights in from flash for every token and sits there for
            // minutes. Someone reported exactly that: five minutes, then nothing.
            // Forty-five seconds with no first word is not "thinking", so stop
            // and say what happened rather than let them wait.
            val watchdog = launch {
                kotlinx.coroutines.delay(45_000)
                if (firstWordAt == 0L) {
                    stalled = true
                    Engines.engine().stop()
                }
            }
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                engineNote = "The reply stopped unexpectedly. You can try again or choose a smaller model."
            } finally { watchdog.cancel() }
            if (stalled) {
                Perf.endLive()
                finishStream(
                    aiId,
                    "${m.name} could not get going - after 45 seconds it had not produced " +
                        "a word, which usually means the phone is out of memory and paging " +
                        "the model in from storage. Close other apps and try again, or pick " +
                        "a smaller AI from the menu.",
                )
                return@launch
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
            finishStream(aiId, sb.toString().ifBlank { "This model could not produce a reply. Try a shorter message or choose a smaller AI." })
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (streamingMessageId == aiId) finishStream(aiId, "The model stopped unexpectedly. Try again or choose a smaller AI.")
            } finally {
                if (Safety.crashedOn(ctx) == m.id) Safety.cancelLoad(ctx, m.id)
                if (streamingMessageId == aiId) {
                    loadingModel = false
                    generating = false
                    streamingMessageId = null
                    Perf.endLive()
                }
            }
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
                // Terse on purpose. A small model parrots its system prompt, so a
                // sentence telling it to introduce itself made it introduce itself
                // at the top of every single answer.
                "You are $name. Answer only what the user asked, with no greeting " +
                    "and no introduction. Keep answers short unless asked for detail."
            },
        )
        // An instruction is not enough at half a billion parameters. Told only
        // "you are Qwen 0.5B", the model still answered "I am a large language
        // model created by Anthropic", because that sentence pattern is far more
        // common in its training data than the truth about itself. A worked
        // example outweighs an instruction at this size, so identity questions
        // get one - and only they do, since every extra turn is prompt the phone
        // must re-read before it can say a word.
        if (asksWhoItIs(user)) {
            out += Turn("user", "Who are you? Who made you?")
            // Purely positive. The first draft said "I am not made by the
            // company behind any chat website" and the model answered "I was
            // created by the company behind any chat website" - it dropped the
            // "not". Every word in this example must be true without a negation.
            out += Turn(
                "assistant",
                "I am $name, an open model made by ${makerOf(model)}. " +
                    "I run locally on this phone, and nothing you type here is sent anywhere.",
            )
        }
        messages
            .dropLast(2)
            .takeLast(8)
            .filter { it.text.isNotBlank() }
            .forEach { out += Turn(if (it.role == "me") "user" else "assistant", it.text) }
        out += Turn("user", user)
        return out
    }

    /**
     * Does this message ask the model what it is? Deliberately broad - a false
     * positive costs one short example turn, a false negative costs the model
     * claiming to be someone else's product in front of a stranger.
     */
    /** The organisation that trained the weights, from the model id. */
    private fun makerOf(m: ModelEntry?): String {
        val id = (m?.id ?: "").lowercase()
        return when {
            id.startsWith("qwen") -> "Alibaba"
            id.startsWith("gemma") -> "Google"
            id.startsWith("llama") -> "Meta"
            id.startsWith("phi") -> "Microsoft"
            id.startsWith("gpt") -> "OpenAI"
            id.startsWith("deepseek") -> "DeepSeek"
            id.startsWith("mistral") -> "Mistral AI"
            id.startsWith("smol") -> "Hugging Face"
            else -> "an open research team"
        }
    }

    private fun asksWhoItIs(text: String): Boolean {
        val t = text.lowercase()
        val aboutIt = listOf(
            "who are you", "what are you", "who made you", "who created you",
            "who built you", "who developed you", "what model", "which model",
            "your name", "are you chatgpt", "are you claude", "are you gpt",
            "are you gemini", "are you an ai", "introduce yourself",
            "tell me about yourself", "what is your name",
        )
        return aboutIt.any { t.contains(it) }
    }

    private fun finishStream(aiId: Long, finalText: String) {
        store.updateMessage(aiId, finalText)
        if (streamingMessageId != aiId) return
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
        Perf.endLive()
    }

    // ---------------------------------------------------------------- install

    /** Opens the requirements sheet. Nothing downloads until they confirm. */
    fun askInstall(m: ModelEntry) {
        device = DeviceProfiler.read(ctx)
        if (!device.supported) {
            toast = "This phone cannot run this version of the AI engine. A 64-bit ARM processor with dot-product and half-precision support is required."
            return
        }
        pendingInstall = m
        drawerOpen = false
    }

    fun dismissInstall() {
        pendingInstall = null
    }

    fun confirmInstall() {
        val m = pendingInstall ?: return
        pendingInstall = null
        install(m)
    }

    /**
     * Bridges the worker's result back into compose state.
     *
     * The worker records the install in SQLite, but nothing about a database row
     * changing redraws a screen - the first real download completed perfectly and
     * the UI carried on saying "not installed" over 428 MB of model. Watching the
     * WorkInfo is what turns the file arriving into the button changing.
     */
    private fun observeDownload(modelId: String) {
        if (!observedDownloads.add(modelId)) return
        viewModelScope.launch {
            WorkManager.getInstance(ctx)
                .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.tagFor(modelId))
                .collect { infos ->
                    val info = ModelDownloadWorker.currentInfo(ctx, modelId, infos) ?: return@collect
                    when (info.state) {
                        WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                            downloadErrors.remove(modelId)
                            activeDownloads[modelId] =
                                info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            downloadErrors.remove(modelId)
                            activeDownloads.remove(modelId)
                            refresh()
                            val name = catalog.firstOrNull { it.id == modelId }?.name ?: modelId
                            toast = "$name is ready"
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            activeDownloads.remove(modelId)
                            refresh()
                            val message = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                                ?: "The download stopped. Tap install to resume."
                            downloadErrors[modelId] = message
                            toast = message
                        }
                        else -> Unit
                    }
                }
        }
    }

    fun install(m: ModelEntry) {
        device = DeviceProfiler.read(ctx)
        if (!device.supported) { toast = "This phone does not support the AI engine."; return }
        downloadErrors.remove(m.id)
        observeDownload(m.id)
        viewModelScope.launch {
            try { ModelDownloadWorker.start(ctx, m.id, wifiOnly); toast = if (wifiOnly) "Installing ${m.name} · waiting for Wi-Fi if needed" else "Installing ${m.name}" }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { toast = "Could not start the download. Please try again." }
        }
    }

    fun cancelInstall(m: ModelEntry) {
        ModelDownloadWorker.cancel(ctx, m.id)
    }

    fun uninstall(id: String, alsoChats: Boolean) {
        if (generating && modelId == id) stopGeneration()
        if (Engines.currentModelId == id) Engines.release()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(ctx).cancelUniqueWork(ModelDownloadWorker.tagFor(id)).result.get()
                ModelDownloadWorker.fileLock(id).withLock {
                    val target = ModelDownloadWorker.fileFor(ctx, id)
                    target.delete()
                    app.llmobi.download.ResumableDownload.partFor(target).delete()
                    app.llmobi.download.ResumableDownload.metaFor(target).delete()
                    store.uninstall(id, alsoChats)
                }
            }
            app.llmobi.shortcut.Shortcuts.remove(ctx, id)
            refresh()
            toast = "Removed"
        }
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
