package com.kinetica.keyboard.onboarding

/**
 * The gesture tutor's lessons, and how a word splits between the thumbs: consecutive letters
 * on one half of the board are one stroke of that thumb, a single letter is a tap. The words
 * avoid `g` and `v`, whose centres sit on the midline, so the picture never has to guess a side.
 */
object TutorScript {

    /** One lesson: the word, whether both thumbs share it, and which hint explains it. */
    class Lesson(val word: String, val twoThumbs: Boolean, val hint: Hint)

    enum class Hint { ONE_THUMB, SWIPE_AND_TAP, TWO_SWIPES, MIXED, ALTERNATE }

    /** One thumb's part of a word, in the order it is written. */
    data class Stroke(val left: Boolean, val letters: String) {
        val isTap: Boolean get() = letters.length == 1
    }

    val LESSONS = listOf(
        Lesson("hello", twoThumbs = false, hint = Hint.ONE_THUMB),
        Lesson("stream", twoThumbs = true, hint = Hint.SWIPE_AND_TAP),
        Lesson("minute", twoThumbs = true, hint = Hint.TWO_SWIPES),
        Lesson("keyboard", twoThumbs = true, hint = Hint.MIXED),
        Lesson("quiet", twoThumbs = true, hint = Hint.ALTERNATE),
    )

    fun strokes(word: String, twoThumbs: Boolean, isLeft: (Char) -> Boolean): List<Stroke> {
        if (word.isEmpty()) return emptyList()
        if (!twoThumbs) return listOf(Stroke(isLeft(word[0]), word))
        val out = ArrayList<Stroke>()
        var side = isLeft(word[0])
        val run = StringBuilder().append(word[0])
        for (c in word.substring(1)) {
            val s = isLeft(c)
            if (s != side) {
                out.add(Stroke(side, run.toString()))
                run.setLength(0)
                side = s
            }
            run.append(c)
        }
        out.add(Stroke(side, run.toString()))
        return out
    }

    /** Whether [typed] finishes [word]: its last word, case and trailing spaces aside. */
    fun done(typed: CharSequence, word: String): Boolean =
        typed.trim().split(' ', '\n').lastOrNull()?.trimEnd('.', ',', '!', '?')?.equals(word, ignoreCase = true) == true
}
