package com.kinetica.keyboard.engine.models

/** Which thumb a pointer belongs to, assigned from its initial X position. */
enum class StreamId { LEFT, RIGHT }

/** One raw touch sample in kw coordinates. */
data class PathPoint(val x: Float, val y: Float, val t: Long)

/** Interval during which a swipe path rested on one letter key. */
data class KeyContact(val code: Int, val tEnter: Long, val tExit: Long)

/**
 * A stretch of a swipe where the pointer stopped moving.
 *
 * This is the user's own letter-boundary signal: in alternating dual-thumb
 * typing, a thumb parks while the other one produces letters. Recording it is
 * what lets a merge interleave gesture legs by *when they happened* instead of
 * inferring boundaries from path shape, which is what every Tier-1 split
 * mechanism has to do.
 *
 * [enterIdx]/[exitIdx] index the owning token's `rawPath`; the times are the
 * run's real endpoints. A run qualifies as a dwell by staying within
 * DWELL_RADIUS_KW of its first sample for at least DWELL_MIN_MS, so its start
 * may sit up to that radius before the pointer fully settled (and its end the
 * same distance into the resumed leg). That imprecision is harmless by
 * construction: either side of the boundary still lands on the rest key, well
 * inside the matcher's endpoint radius.
 */
data class Dwell(val enterIdx: Int, val exitIdx: Int, val tEnter: Long, val tExit: Long)

/**
 * The merge-ready unit of input. A word in progress is a list of tokens from
 * both streams; sorting by [tStart] yields the primary letter order because a
 * gesture's letters begin being produced when the gesture starts.
 */
sealed class InputToken {
    abstract val streamId: StreamId
    abstract val tStart: Long
    abstract val tEnd: Long
}

/**
 * Pointer lifted quickly without travelling: a literal letter. [code] is the
 * key under the DOWN position. [x]/[y] (kw) keep the exact touch point for the
 * fuzzy-substitution fallback pass.
 */
data class TapToken(
    override val streamId: StreamId,
    val code: Int,
    val x: Float,
    val y: Float,
    val longPress: Boolean,
    override val tStart: Long,
    override val tEnd: Long,
) : InputToken()

/**
 * A swipe gesture. Carries both the resampled raw path (DTW input: quantizing
 * to key centers would destroy the corner geometry that separates near-tie
 * words) and the hysteresis-deduped key contacts (pruning and UI callbacks).
 *
 * [softStart] marks the second half of a mid-swipe split (see
 * MergeAlternatives.splitSwipe): such a half resumes mid-word after a rest, so
 * its first letter may sit anywhere along the resumed path rather than at the
 * path's start point, and the matcher relaxes its start-letter gate accordingly.
 *
 * [softEnd] is the symmetric flag on the FIRST half of a split: the cut lands
 * at the raw sample nearest the interrupting tap, which is mid-travel whenever
 * the thumb was moving when the other thumb tapped ("quindi"), so the half's
 * last letter may sit anywhere along its path rather
 * than at the cut point, and the matcher relaxes its end-letter gate.
 *
 * [dwells] are the pauses GestureStream observed inside this gesture, in time
 * order. Populated for whole gestures only (split halves inherit none - their
 * boundaries are already explicit). The decode path does not read this
 * directly: it exists so DecodeTrace can report real on-device dwell timings,
 * and so the merge can use them as a secondary cut source.
 */
class SwipeToken(
    override val streamId: StreamId,
    val rawPath: List<PathPoint>,
    val resampled: FloatArray,       // RESAMPLE_N (x, y) pairs, kw
    val keyContacts: List<KeyContact>,
    val arcLen: Float,               // kw
    override val tStart: Long,
    override val tEnd: Long,
    val softStart: Boolean = false,
    val softEnd: Boolean = false,
    val dwells: List<Dwell> = emptyList(),
) : InputToken()
