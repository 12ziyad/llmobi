package app.llmobi.engine

import org.junit.Assert.*
import org.junit.Test

class Utf8TokenDecoderTest {
    @Test fun multilingualTextSurvivesEveryPossibleTokenBoundary() {
        val text = "Hello മലയാളം हिन्दी العربية 中文 😀🌍\u0000 done"
        val bytes = text.toByteArray()
        for (boundary in 0..bytes.size) {
            val decoder = Utf8TokenDecoder()
            val actual = decoder.append(bytes.copyOfRange(0, boundary)) + decoder.append(bytes.copyOfRange(boundary, bytes.size), end = true)
            assertEquals("split at $boundary", text, actual)
        }
    }
    @Test fun byteByByteOutputDoesNotEmitBrokenCharacters() {
        val decoder = Utf8TokenDecoder()
        val text = "ഹലോ 👋"
        assertEquals(text, text.toByteArray().joinToString("") { decoder.append(byteArrayOf(it)) } + decoder.append(byteArrayOf(), end = true))
    }
}
