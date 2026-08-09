package com.kinetica.keyboard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [UserWord::class, ChordShortcut::class],
    version = 2,
    exportSchema = false,
)
abstract class KineticaDb : RoomDatabase() {
    abstract fun userWords(): UserWordDao
    abstract fun chordShortcuts(): ChordShortcutDao

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

        fun get(context: Context): KineticaDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KineticaDb::class.java,
                    "user_dict.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
