package app.llmobi.shortcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.llmobi.ChatActivity
import app.llmobi.data.ModelEntry

/**
 * The home-screen icons.
 *
 * This is the feature the whole product is built around: one APK, but each model
 * gets its own icon, its own Recents card and its own chat. The entire mechanism
 * is a pinned shortcut carrying llmobi://chat/model/<id>.
 */
object Shortcuts {

    fun deepLink(modelId: String): Uri = Uri.parse("llmobi://chat/model/$modelId")

    fun chatIntent(ctx: Context, modelId: String): Intent =
        Intent(Intent.ACTION_VIEW, deepLink(modelId), ctx, ChatActivity::class.java).apply {
            // A distinct affinity per model is what gives each one its own card in
            // Recents, so it reads as a separate app rather than a re-used screen.
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra("model_id", modelId)
        }

    fun canPin(ctx: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)

    /**
     * Asks Android to place the icon. The system shows its own confirm dialog -
     * we cannot place it silently, and should not want to.
     */
    fun requestPin(ctx: Context, model: ModelEntry): Boolean {
        if (!canPin(ctx)) return false
        val info = ShortcutInfoCompat.Builder(ctx, "model_${model.id}")
            .setShortLabel(model.name)
            .setLongLabel(model.name)
            .setIcon(IconCompat.createWithAdaptiveBitmap(icon(model)))
            .setIntent(chatIntent(ctx, model.id))
            .build()
        return ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }

    fun remove(ctx: Context, modelId: String) {
        ShortcutManagerCompat.removeLongLivedShortcuts(ctx, listOf("model_$modelId"))
        ShortcutManagerCompat.disableShortcuts(
            ctx, listOf("model_$modelId"),
            "This AI was removed from your phone.",
        )
    }

    /**
     * Draws the icon at install time: the model's initial on its own gradient.
     * Adaptive icons get cropped to whatever shape the launcher uses, so the
     * artwork sits inside the safe centre area.
     */
    private fun icon(model: ModelEntry): Bitmap {
        val size = 320
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                model.colorStart.toInt(), model.colorEnd.toInt(),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF101214.toInt()
            textSize = size * 0.34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val cy = size / 2f - (text.descent() + text.ascent()) / 2f
        c.drawText(model.iconLetter, size / 2f, cy, text)

        return bmp
    }
}
