package com.kinetica.keyboard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [UserWord::class, ChordShortcut::class, BlockedWord::class, EmojiUse::class],
    version = 4,
    exportSchema = false,
)
abstract class KineticaDb : RoomDatabase() {
    abstract fun userWords(): UserWordDao
    abstract fun chordShortcuts(): ChordShortcutDao
    abstract fun blockedWords(): BlockedWordDao
    abstract fun emojiUses(): EmojiUseDao

    companion object {
        @Volatile
        private var instance: KineticaDb? = null

        /**
         * v1 -> v2: user_words gains a language partition. Pre-v2 rows carry
         * no language information, so they are attributed to English (the
         * default language) rather than dropped; an Italian-first user loses
         * nothing they cannot re-earn in a few commits.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE user_words_v2 (" +
                        "word TEXT NOT NULL, lang TEXT NOT NULL, " +
                        "frequency INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(word, lang))",
                )
                db.execSQL(
                    "INSERT INTO user_words_v2 (word, lang, frequency, updatedAt) " +
                        "SELECT word, 'en', frequency, updatedAt FROM user_words",
                )
                db.execSQL("DROP TABLE user_words")
                db.execSQL("ALTER TABLE user_words_v2 RENAME TO user_words")
            }
        }

        /**
         * v2 -> v3: the block list arrives as a new table, so nothing existing
         * is read, rewritten or dropped. Learned words and chords are untouched
         * by construction, which is the whole reason to add a table rather than
         * a column on user_words.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS blocked_words (" +
                        "word TEXT NOT NULL, lang TEXT NOT NULL, " +
                        "addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(word, lang))",
                )
            }
        }

        /**
         * v3 -> v4: emoji use counts arrive as a new table, the same shape as
         * v2 -> v3. Nothing existing is read, rewritten or dropped.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS emoji_uses (" +
                        "emoji TEXT NOT NULL, count INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(emoji))",
                )
            }
        }

        fun get(context: Context): KineticaDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KineticaDb::class.java,
                    "user_dict.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
    }
}
