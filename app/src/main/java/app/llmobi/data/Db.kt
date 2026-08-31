package app.llmobi.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLite rather than Room.
 *
 * The queries here are simple and the schema is small, so Room's annotation
 * processing would add a build dependency without buying much. Everything below
 * is deliberately boring so it cannot fail in surprising ways.
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "llmobi.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE installed (
              model_id   TEXT PRIMARY KEY,
              path       TEXT NOT NULL,
              bytes      INTEGER NOT NULL,
              installed  INTEGER NOT NULL,
              last_used  INTEGER NOT NULL DEFAULT 0,
              pinned_shortcut INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE chats (
              id       INTEGER PRIMARY KEY AUTOINCREMENT,
              model_id TEXT NOT NULL,
              title    TEXT NOT NULL,
              pinned   INTEGER NOT NULL DEFAULT 0,
              created  INTEGER NOT NULL,
              updated  INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
              id      INTEGER PRIMARY KEY AUTOINCREMENT,
              chat_id INTEGER NOT NULL,
              role    TEXT NOT NULL,
              text    TEXT NOT NULL,
              created INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE settings (
              model_id  TEXT PRIMARY KEY,
              system    TEXT NOT NULL DEFAULT '',
              creativity REAL NOT NULL DEFAULT 0.7,
              memory    TEXT NOT NULL DEFAULT 'normal',
              nickname  TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_msg_chat ON messages(chat_id)")
        db.execSQL("CREATE INDEX idx_chat_model ON chats(model_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // Nothing to migrate yet. When there is, add stepwise ALTERs here -
        // never drop a user's chat history.
    }
}

// ---------------------------------------------------------------- models

data class Installed(
    val modelId: String,
    val path: String,
    val bytes: Long,
    val installedAt: Long,
    val lastUsed: Long,
    val pinnedShortcut: Boolean,
)

data class Chat(
    val id: Long,
    val modelId: String,
    val title: String,
    val pinned: Boolean,
    val updated: Long,
)

data class Message(
    val id: Long,
    val chatId: Long,
    val role: String, // "me" or "ai"
    val text: String,
    val created: Long,
)

data class ModelSettings(
    val modelId: String,
    val system: String,
    val creativity: Float,
    val memory: String, // short | normal | long
    val nickname: String,
)

// ---------------------------------------------------------------- store

class Store(ctx: Context) {

    private val helper = Db(ctx.applicationContext)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---- installed models

    fun installed(): List<Installed> {
        val out = mutableListOf<Installed>()
        db.rawQuery(
            "SELECT model_id,path,bytes,installed,last_used,pinned_shortcut FROM installed ORDER BY last_used DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += Installed(
                    c.getString(0), c.getString(1), c.getLong(2),
                    c.getLong(3), c.getLong(4), c.getInt(5) == 1,
                )
            }
        }
        return out
    }

    fun isInstalled(modelId: String): Boolean =
        db.rawQuery("SELECT 1 FROM installed WHERE model_id=?", arrayOf(modelId)).use { it.moveToFirst() }

    fun installedOne(modelId: String): Installed? =
        installed().firstOrNull { it.modelId == modelId }

    fun markInstalled(modelId: String, path: String, bytes: Long) {
        db.insertWithOnConflict(
            "installed", null,
            ContentValues().apply {
                put("model_id", modelId)
                put("path", path)
                put("bytes", bytes)
                put("installed", System.currentTimeMillis())
                put("last_used", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun touch(modelId: String) {
        db.execSQL("UPDATE installed SET last_used=? WHERE model_id=?", arrayOf(System.currentTimeMillis(), modelId))
    }

    fun setShortcutPinned(modelId: String, pinned: Boolean) {
        db.execSQL("UPDATE installed SET pinned_shortcut=? WHERE model_id=?", arrayOf(if (pinned) 1 else 0, modelId))
    }

    /** Removes the model row. Chat history is kept unless [alsoChats]. */
    fun uninstall(modelId: String, alsoChats: Boolean) {
        db.delete("installed", "model_id=?", arrayOf(modelId))
        if (alsoChats) {
            db.rawQuery("SELECT id FROM chats WHERE model_id=?", arrayOf(modelId)).use { c ->
                while (c.moveToNext()) db.delete("messages", "chat_id=?", arrayOf(c.getLong(0).toString()))
            }
            db.delete("chats", "model_id=?", arrayOf(modelId))
        }
    }

    // ---- chats

    fun chats(modelId: String): List<Chat> {
        val out = mutableListOf<Chat>()
        db.rawQuery(
            "SELECT id,model_id,title,pinned,updated FROM chats WHERE model_id=? ORDER BY pinned DESC, updated DESC",
            arrayOf(modelId),
        ).use { c ->
            while (c.moveToNext()) {
                out += Chat(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getLong(4))
            }
        }
        return out
    }

    fun chatCount(modelId: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM chats WHERE model_id=?", arrayOf(modelId)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun newChat(modelId: String, title: String): Long {
        val now = System.currentTimeMillis()
        return db.insert(
            "chats", null,
            ContentValues().apply {
                put("model_id", modelId)
                put("title", title.take(60))
                put("created", now)
                put("updated", now)
            },
        )
    }

    fun renameChat(id: Long, title: String) {
        db.execSQL("UPDATE chats SET title=? WHERE id=?", arrayOf(title.take(60), id))
    }

    fun pinChat(id: Long, pinned: Boolean) {
        db.execSQL("UPDATE chats SET pinned=? WHERE id=?", arrayOf(if (pinned) 1 else 0, id))
    }

    fun deleteChat(id: Long) {
        db.delete("messages", "chat_id=?", arrayOf(id.toString()))
        db.delete("chats", "id=?", arrayOf(id.toString()))
    }

    // ---- messages

    fun messages(chatId: Long): List<Message> {
        val out = mutableListOf<Message>()
        db.rawQuery(
            "SELECT id,chat_id,role,text,created FROM messages WHERE chat_id=? ORDER BY id ASC",
            arrayOf(chatId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += Message(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4))
            }
        }
        return out
    }

    fun addMessage(chatId: Long, role: String, text: String): Long {
        val now = System.currentTimeMillis()
        val id = db.insert(
            "messages", null,
            ContentValues().apply {
                put("chat_id", chatId)
                put("role", role)
                put("text", text)
                put("created", now)
            },
        )
        db.execSQL("UPDATE chats SET updated=? WHERE id=?", arrayOf(now, chatId))
        return id
    }

    fun updateMessage(id: Long, text: String) {
        db.execSQL("UPDATE messages SET text=? WHERE id=?", arrayOf(text, id))
    }

    fun historyBytes(): Long =
        db.rawQuery("SELECT COALESCE(SUM(LENGTH(text)),0) FROM messages", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    // ---- per model settings

    fun settings(modelId: String): ModelSettings =
        db.rawQuery(
            "SELECT model_id,system,creativity,memory,nickname FROM settings WHERE model_id=?",
            arrayOf(modelId),
        ).use { c ->
            if (c.moveToFirst()) {
                ModelSettings(c.getString(0), c.getString(1), c.getFloat(2), c.getString(3), c.getString(4))
            } else {
                ModelSettings(modelId, "", 0.7f, "normal", "")
            }
        }

    fun saveSettings(s: ModelSettings) {
        db.insertWithOnConflict(
            "settings", null,
            ContentValues().apply {
                put("model_id", s.modelId)
                put("system", s.system)
                put("creativity", s.creativity)
                put("memory", s.memory)
                put("nickname", s.nickname)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}
