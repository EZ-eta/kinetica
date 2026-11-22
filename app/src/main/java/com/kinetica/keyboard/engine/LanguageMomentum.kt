/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

package com.kinetica.keyboard.engine

/**
 * Which enabled language the user is typing now, as weights over the resident languages.
 *
 * Each language keeps a standing that moves a [step] of the way toward every committed word's
 * score in it, zero where it does not hold the word. The front-runner weighs one; the others
 * share one weight that falls from one toward [damped] as the front-runner's lead over the
 * nearest rival grows. A close race keeps every language near equal, a clear lead damps the rest
 * without silencing them. The constants are swept on the captured corpus.
 *
 * Main thread only. The composer reads a snapshot of [weightsFor].
 */
class LanguageMomentum(
    private val step: Float = KineticaConstants.MOMENTUM_STEP,
    private val damped: Float = KineticaConstants.MOMENTUM_DAMPED_WEIGHT,
    private val fullLead: Float = KineticaConstants.MOMENTUM_FULL_LEAD,
) {
    private val standing = LinkedHashMap<String, Float>()

    /** A committed word's score in each resident language, 0 to 1, 0 where it is not held. */
    fun observe(scores: Map<String, Float>) {
        for ((lang, s) in scores) {
            val v = standing[lang] ?: s
            standing[lang] = v + step * (s - v)
        }
    }

    fun front(): String? = standing.maxByOrNull { it.value }?.key

    /** The front-runner among [languages]. */
    fun front(languages: Collection<String>): String? =
        standing.filterKeys { it in languages }.maxByOrNull { it.value }?.key

    /**
     * One weight per language in [languages]: 1 for the front-runner, the shared weight for the
     * rest. Only those languages compete: a language left behind by a switch keeps its standing,
     * and if it led it would damp every resident language alike.
     */
    fun weightsFor(languages: List<String>): Map<String, Float> {
        val held = standing.filterKeys { it in languages }
        val lead = held.maxByOrNull { it.value }?.key ?: return languages.associateWith { 1f }
        val sorted = held.values.sortedDescending()
        val gap = if (sorted.size < 2) 0f else sorted[0] - sorted[1]
        val rest = 1f - (1f - damped) * minOf(1f, gap / fullLead)
        return languages.associateWith { if (it == lead) 1f else rest }
    }

    /** Drops the standing of every language not in [languages], when the resident set changes. */
    fun retain(languages: Collection<String>) {
        standing.keys.retainAll(languages.toSet())
    }

    fun forget() = standing.clear()
}
