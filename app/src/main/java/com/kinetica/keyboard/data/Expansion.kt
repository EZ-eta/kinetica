package com.kinetica.keyboard.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One text expansion: typing [trigger] and firing the expandify action replaces it with
 * [target].
 *
 * Keyed by text, unlike [ChordShortcut], which is keyed by a key: the trigger is whatever is
 * already written at the cursor, so `.`, `vv`, `^^` and `(-.-)'` are ordinary rows.
 *
 * Chaining, loops and shared targets need no code. The lookup reads the cursor each time, so
 * firing twice on `^^` gives `^_^` then `^__^`, and a loop is a row pointing back.
 *
 * [position] orders several targets for one trigger. Only position 0 is read today; the
 * column exists so the picker can arrive without a second migration.
 */
@Entity(tableName = "expansions", primaryKeys = ["triggerText", "position"])
data class Expansion(
    /**
     * Stored as `triggerText`: TRIGGER is a SQL keyword and the migration's CREATE TABLE is
     * hand-written for SQLite 3.18 on minSdk 26.
     */
    @ColumnInfo(name = "triggerText") val trigger: String,
    val position: Int,
    val target: String,
)
