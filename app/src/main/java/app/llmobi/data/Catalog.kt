package app.llmobi.data

/**
 * One catalog record. This is exactly the shape the Cloudflare Worker serves at
 * /v1/catalog - the app is deliberately dumb and never decides any of this itself.
 *
 * sizeLabel / speedHint are written by us on the server so the phone never has to
 * do marketing maths. minRamMb is precomputed as weights + KV cache + overhead.
 */
data class ModelEntry(
    val id: String,
    val name: String,
    val tagline: String,
    val tier: Tier,
    val category: String,
    val iconLetter: String,
    val colorStart: Long,
    val colorEnd: Long,
    val sizeLabel: String,
    val speedHint: String,
    val fileBytes: Long,
    val minRamMb: Int,
    val ctxDefault: Int,
    val arch: String,
    val quant: String,
    val url: String,
    val sha256: String,
    val license: String,
)

enum class Tier(val label: String) {
    TINY("Tiny"), FAST("Fast"), POWERFUL("Powerful"), PRO("Pro"), EXTREME("Extreme")
}

private const val GB = 1_073_741_824L
private fun gb(x: Double): Long = (x * GB).toLong()

/**
 * The launch catalog - 20 models.
 *
 * Bundled so the store works on first launch with no network at all. The Worker
 * refreshes it in the background and the fresher copy wins.
 */
object Catalog {

    val models: List<ModelEntry> = listOf(

        // ---------------- TINY : runs on nearly any phone ----------------
        ModelEntry(
            id = "qwen25-05b", name = "Qwen 0.5B",
            tagline = "Tiny and instant. Good for quick questions.",
            tier = Tier.TINY, category = "general", iconLetter = "Q",
            colorStart = 0xFF7BD4F5, colorEnd = 0xFF3C8FD0,
            sizeLabel = "0.40 GB", speedHint = "very fast",
            fileBytes = 428730208L, minRamMb = 1012, ctxDefault = 2048,
            // Q4_0 rather than the usual Q4_K_M or Q5_K_M on purpose. Every ARM
            // chip we care about reports asimddp, and llama.cpp repacks Q4_0 to
            // use those dot-product instructions. Measured on a Dimensity 6100+:
            // Q4_0 17.2 tok/s, Q4_K_M 15.0, Q5_K_M 14.4 - and it is the smallest
            // download of the three. On the weak phones this tier exists for,
            // that trade is worth the small quality cost.
            arch = "qwen2", quant = "Q4_0",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf",
            sha256 = "", license = "apache-2.0",
        ),
        ModelEntry(
            id = "llama32-1b", name = "Llama 1B",
            tagline = "Meta's small everyday assistant.",
            tier = Tier.TINY, category = "general", iconLetter = "L",
            colorStart = 0xFFF5C77B, colorEnd = 0xFFD08A34,
            sizeLabel = "0.95 GB", speedHint = "very fast",
            fileBytes = 1021800576L, minRamMb = 1719, ctxDefault = 4096,
            arch = "llama", quant = "Q6_K",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q6_K.gguf",
            sha256 = "", license = "llama-3.2-community",
        ),
        ModelEntry(
            id = "gemma3-1b", name = "Gemma 1B",
            tagline = "Google's compact model. Neat writer.",
            tier = Tier.TINY, category = "general", iconLetter = "G",
            colorStart = 0xFF9BE7C4, colorEnd = 0xFF3FA47C,
            sizeLabel = "0.94 GB", speedHint = "very fast",
            fileBytes = 1011738880L, minRamMb = 1707, ctxDefault = 4096,
            arch = "gemma3", quant = "Q6_K",
            url = "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q6_K.gguf",
            sha256 = "", license = "gemma-terms",
        ),
        ModelEntry(
            id = "qwen3-17b", name = "Qwen 1.7B",
            tagline = "Small but sharp. Handles many languages.",
            tier = Tier.TINY, category = "multilingual", iconLetter = "Q",
            colorStart = 0xFF7BD4F5, colorEnd = 0xFF3C8FD0,
            sizeLabel = "1.4 GB", speedHint = "fast",
            fileBytes = 1471805856L, minRamMb = 2255, ctxDefault = 4096,
            arch = "qwen3", quant = "Q5_K_M",
            url = "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q5_K_M.gguf",
            sha256 = "", license = "apache-2.0",
        ),

        // ---------------- FAST : 6-8 GB phones ----------------
        ModelEntry(
            id = "llama32-3b", name = "Llama 3B",
            tagline = "Solid all-rounder for everyday questions.",
            tier = Tier.FAST, category = "general", iconLetter = "L",
            colorStart = 0xFFF5C77B, colorEnd = 0xFFD08A34,
            sizeLabel = "1.9 GB", speedHint = "fast",
            fileBytes = 2019377696L, minRamMb = 2908, ctxDefault = 4096,
            arch = "llama", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sha256 = "", license = "llama-3.2-community",
        ),
        ModelEntry(
            id = "phi4-mini", name = "Phi 3.8B",
            tagline = "Microsoft's small model. Strong at maths.",
            tier = Tier.FAST, category = "reasoning", iconLetter = "P",
            colorStart = 0xFFF6A5A5, colorEnd = 0xFFCE5757,
            sizeLabel = "2.3 GB", speedHint = "fast",
            fileBytes = 2491874688L, minRamMb = 3471, ctxDefault = 4096,
            arch = "phi3", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/microsoft_Phi-4-mini-instruct-GGUF/resolve/main/microsoft_Phi-4-mini-instruct-Q4_K_M.gguf",
            sha256 = "", license = "mit",
        ),
        ModelEntry(
            id = "qwen3-4b", name = "Qwen 4B",
            tagline = "Thinks things through. Many languages.",
            tier = Tier.FAST, category = "reasoning", iconLetter = "Q",
            colorStart = 0xFF7BD4F5, colorEnd = 0xFF3C8FD0,
            sizeLabel = "2.3 GB", speedHint = "fast",
            fileBytes = 2497280960L, minRamMb = 3477, ctxDefault = 4096,
            arch = "qwen3", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/resolve/main/Qwen_Qwen3-4B-Q4_K_M.gguf",
            sha256 = "", license = "apache-2.0",
        ),
        ModelEntry(
            id = "gemma3-4b", name = "Gemma 4B",
            tagline = "Everyday AI. Can also look at pictures.",
            tier = Tier.FAST, category = "vision", iconLetter = "G",
            colorStart = 0xFF9BE7C4, colorEnd = 0xFF3FA47C,
            sizeLabel = "2.3 GB", speedHint = "fast",
            fileBytes = 2489758112L, minRamMb = 3469, ctxDefault = 4096,
            arch = "gemma3", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf",
            sha256 = "", license = "gemma-terms",
        ),

        // ---------------- POWERFUL : 12 GB phones ----------------
        ModelEntry(
            id = "mistral-7b", name = "Mistral 7B",
            tagline = "Fast European model. Clean writing.",
            tier = Tier.POWERFUL, category = "general", iconLetter = "M",
            colorStart = 0xFFFFB088, colorEnd = 0xFFD1591F,
            sizeLabel = "4.1 GB", speedHint = "steady",
            fileBytes = 4372812000L, minRamMb = 5713, ctxDefault = 4096,
            arch = "llama", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            sha256 = "", license = "apache-2.0",
        ),
        ModelEntry(
            id = "deepseek-r1-7b", name = "DeepSeek 7B",
            tagline = "Shows its thinking before answering.",
            tier = Tier.POWERFUL, category = "reasoning", iconLetter = "D",
            colorStart = 0xFFC3B0F7, colorEnd = 0xFF6E58C9,
            sizeLabel = "4.4 GB", speedHint = "steady",
            fileBytes = 4683073504L, minRamMb = 6083, ctxDefault = 4096,
            arch = "qwen2", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            sha256 = "", license = "mit",
        ),
        ModelEntry(
            id = "qwen25-coder-7b", name = "Qwen Coder 7B",
            tagline = "Writes and fixes code.",
            tier = Tier.POWERFUL, category = "coding", iconLetter = "C",
            colorStart = 0xFF8FE3C4, colorEnd = 0xFF2C8F6B,
            sizeLabel = "4.4 GB", speedHint = "steady",
            fileBytes = 4683074336L, minRamMb = 6083, ctxDefault = 8192,
            arch = "qwen2", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf",
            sha256 = "", license = "apache-2.0",
        ),
        ModelEntry(
            id = "llama31-8b", name = "Llama 8B",
            tagline = "Strong general assistant.",
            tier = Tier.POWERFUL, category = "general", iconLetter = "L",
            colorStart = 0xFFF5C77B, colorEnd = 0xFFD08A34,
            sizeLabel = "4.6 GB", speedHint = "steady",
            fileBytes = 4920739232L, minRamMb = 6366, ctxDefault = 4096,
            arch = "llama", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            sha256 = "", license = "llama-3.1-community",
        ),
        ModelEntry(
            id = "qwen3-8b", name = "Qwen 8B",
            tagline = "Reasoning and 100+ languages.",
            tier = Tier.POWERFUL, category = "multilingual", iconLetter = "Q",
            colorStart = 0xFF7BD4F5, colorEnd = 0xFF3C8FD0,
            sizeLabel = "4.7 GB", speedHint = "steady",
            fileBytes = 5027784224L, minRamMb = 6494, ctxDefault = 4096,
            arch = "qwen3", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/Qwen_Qwen3-8B-GGUF/resolve/main/Qwen_Qwen3-8B-Q4_K_M.gguf",
            sha256 = "", license = "apache-2.0",
        ),

        // ---------------- PRO : 16 GB flagships ----------------
        ModelEntry(
            id = "gemma3-12b", name = "Gemma 12B",
            tagline = "Noticeably smarter. Needs a big phone.",
            tier = Tier.PRO, category = "general", iconLetter = "G",
            colorStart = 0xFF9BE7C4, colorEnd = 0xFF3FA47C,
            sizeLabel = "6.8 GB", speedHint = "slow",
            fileBytes = 7300575264L, minRamMb = 9203, ctxDefault = 4096,
            arch = "gemma3", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/google_gemma-3-12b-it-GGUF/resolve/main/google_gemma-3-12b-it-Q4_K_M.gguf",
            sha256 = "", license = "gemma-terms",
        ),
        ModelEntry(
            id = "qwen3-14b", name = "Qwen 14B",
            tagline = "Serious reasoning power.",
            tier = Tier.PRO, category = "reasoning", iconLetter = "Q",
            colorStart = 0xFF7BD4F5, colorEnd = 0xFF3C8FD0,
            sizeLabel = "8.4 GB", speedHint = "slow",
            fileBytes = 9001753632L, minRamMb = 11231, ctxDefault = 4096,
            arch = "qwen3", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/Qwen_Qwen3-14B-GGUF/resolve/main/Qwen_Qwen3-14B-Q4_K_M.gguf",
            sha256 = "", license = "apache-2.0",
        ),
        ModelEntry(
            id = "deepseek-r1-14b", name = "DeepSeek 14B",
            tagline = "Works through hard problems step by step.",
            tier = Tier.PRO, category = "reasoning", iconLetter = "D",
            colorStart = 0xFFC3B0F7, colorEnd = 0xFF6E58C9,
            sizeLabel = "8.4 GB", speedHint = "slow",
            fileBytes = 8988110240L, minRamMb = 11215, ctxDefault = 4096,
            arch = "qwen2", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-14B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-14B-Q4_K_M.gguf",
            sha256 = "", license = "mit",
        ),
        ModelEntry(
            id = "phi4-14b", name = "Phi 14B",
            tagline = "Excellent at maths and logic.",
            tier = Tier.PRO, category = "reasoning", iconLetter = "P",
            colorStart = 0xFFF6A5A5, colorEnd = 0xFFCE5757,
            sizeLabel = "8.4 GB", speedHint = "slow",
            fileBytes = 9053114816L, minRamMb = 11293, ctxDefault = 4096,
            arch = "phi3", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/phi-4-GGUF/resolve/main/phi-4-Q4_K_M.gguf",
            sha256 = "", license = "mit",
        ),

        // ---------------- EXTREME : experimental ----------------
        ModelEntry(
            id = "gpt-oss-20b", name = "GPT-OSS 20B",
            tagline = "Very capable. Only for huge-memory phones.",
            tier = Tier.EXTREME, category = "general", iconLetter = "O",
            colorStart = 0xFFB8B8B8, colorEnd = 0xFF5A5A5A,
            sizeLabel = "11.3 GB", speedHint = "very slow",
            fileBytes = 12109566624L, minRamMb = 14936, ctxDefault = 4096,
            arch = "gptoss", quant = "Q4",
            url = "https://huggingface.co/ggml-org/gpt-oss-20b-GGUF/resolve/main/gpt-oss-20b-MXFP4.gguf",
            sha256 = "", license = "apache-2.0",
        ),
        ModelEntry(
            id = "gemma3-27b", name = "Gemma 27B",
            tagline = "Near desktop quality. Experimental on phones.",
            tier = Tier.EXTREME, category = "general", iconLetter = "G",
            colorStart = 0xFF9BE7C4, colorEnd = 0xFF3FA47C,
            sizeLabel = "15.4 GB", speedHint = "very slow",
            fileBytes = 16546404992L, minRamMb = 20225, ctxDefault = 4096,
            arch = "gemma3", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/google_gemma-3-27b-it-GGUF/resolve/main/google_gemma-3-27b-it-Q4_K_M.gguf",
            sha256 = "", license = "gemma-terms",
        ),
        ModelEntry(
            id = "qwen3-32b", name = "Qwen 32B",
            tagline = "The largest we list. Almost no phone runs it.",
            tier = Tier.EXTREME, category = "reasoning", iconLetter = "Q",
            colorStart = 0xFF7BD4F5, colorEnd = 0xFF3C8FD0,
            sizeLabel = "18.4 GB", speedHint = "very slow",
            fileBytes = 19762149696L, minRamMb = 24059, ctxDefault = 4096,
            arch = "qwen3", quant = "Q4_K_M",
            url = "https://huggingface.co/bartowski/Qwen_Qwen3-32B-GGUF/resolve/main/Qwen_Qwen3-32B-Q4_K_M.gguf",
            sha256 = "", license = "apache-2.0",
        ),
    )

    fun byId(id: String): ModelEntry? = models.firstOrNull { it.id == id }
}
