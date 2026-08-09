package com.kinetica.keyboard.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ChordShortcutDao {
    @Insert
    fun insert(chord: ChordShortcut): Long

    @Delete
    fun delete(chord: ChordShortcut)

    @Query("SELECT * FROM chord_shortcuts")
    fun all(): List<ChordShortcut>

    @Query("DELETE FROM chord_shortcuts WHERE chord = :chord")
    fun deleteByChord(chord: String)

    /** One expansion per letter: assignment replaces any previous binding. */
    @Transaction
    fun assign(chord: String, expansion: String) {
        deleteByChord(chord)
        insert(ChordShortcut(chord = chord, expansion = expansion))
    }
}
