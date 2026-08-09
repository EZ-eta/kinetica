package com.kinetica.keyboard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-defined chord -> text expansion, managed in ChordSettingsActivity. */
@Entity(tableName = "chord_shortcuts")
data class ChordShortcut(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chord: String,
    val expansion: String,
)
