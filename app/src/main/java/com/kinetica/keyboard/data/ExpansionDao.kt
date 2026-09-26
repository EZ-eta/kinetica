package com.kinetica.keyboard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ExpansionDao {
    @Insert
    fun insert(rows: List<Expansion>)

    @Query("SELECT * FROM expansions ORDER BY triggerText, position")
    fun all(): List<Expansion>

    @Query("DELETE FROM expansions WHERE triggerText = :trigger")
    fun deleteByTrigger(trigger: String)

    /**
     * Replaces every target for [trigger]. Mirrors [ChordShortcutDao.assign]: one trigger
     * owns its whole list, so an edit cannot leave an orphaned position behind.
     */
    @Transaction
    fun assign(trigger: String, targets: List<String>) {
        deleteByTrigger(trigger)
        if (targets.isEmpty()) return
        insert(targets.mapIndexed { i, t -> Expansion(trigger, i, t) })
    }
}
