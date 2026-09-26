package com.kinetica.keyboard.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One text expansion: typing [trigger] and firing the expandify action replaces it with
 * [target].
 *
 * Keyed by the trigger text rather than by a key or a letter index, which is the whole
 * difference from [ChordShortcut]. A chord is a letter you hold a modifier with, so its
 * identity is a 0-25 index and a non-letter cannot be expressed; a trigger is whatever is
 * already written at the cursor, so `.`, `vv`, `^^` and `(-.-)'` are all ordinary rows.
 *
 * **Chaining, loops and shared targets need no mechanism.** The lookup asks what is at the
 * cursor now, so firing twice on `^^` finds `^_^` and then `^__^`, and a chain that ends
 * where it started is a row pointing back. Two triggers may share one target, and
 * that target may itself be a trigger. All three were the hardest-sounding parts of the
 * request and none of them is code.
 *
 * [position] orders several targets for one trigger. Only position 0 is read today; the
 * column ships now so the picker can arrive without a second migration.
 */
@Entity(tableName = "expansions", primaryKeys = ["triggerText", "position"])
data class Expansion(
    /**
     * Stored as `triggerText`, because TRIGGER is a SQL keyword and the migration writes
     * its own CREATE TABLE by hand. SQLite accepts it unquoted today, but minSdk 26 ships
     * 3.18 and a migration is not the place to find out where that stops being true.
     */
    @ColumnInfo(name = "triggerText") val trigger: String,
    val position: Int,
    val target: String,
)
