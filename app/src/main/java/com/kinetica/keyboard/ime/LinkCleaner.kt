package com.kinetica.keyboard.ime

/**
 * Takes tracking parameters out of web links (#26): `utm_*`, the ad networks' click ids, and a
 * few sites' share ids. Pure, so every rule is a JVM test.
 *
 * Only the query string changes, one parameter at a time, and every parameter not on the list
 * stays exactly as written, encoding and order included. A list and not a guess: a parameter we
 * do not know may be the page itself (`v=` on YouTube, `id=` anywhere).
 */
object LinkCleaner {

    /** What [cleanLastLink] replaces: [span] characters before the cursor, with [text]. */
    data class Replacement(val span: Int, val text: String)

    /** [text] with every http(s) link in it cleaned. */
    fun clean(text: String): String =
        LINK.replace(text) { m -> cleanUrl(m.value) }

    /**
     * The link that ends where [before] ends, trailing whitespace aside, cleaned: what the
     * clean-links action does with no selection, right after a link is pasted. Null when the
     * last word is not a link or is clean already.
     */
    fun cleanLastLink(before: CharSequence): Replacement? {
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        val word = before.substring(start, end)
        if (!LINK.containsMatchIn(word)) return null
        val cleaned = clean(word)
        if (cleaned == word) return null
        return Replacement(before.length - start, cleaned + before.substring(end))
    }

    /** One link. Text after it that prose put there, a full stop or a bracket, stays outside. */
    fun cleanUrl(link: String): String {
        var end = link.length
        while (end > 0 && link[end - 1] in TRAILING_PROSE) end--
        val url = link.substring(0, end)
        val tail = link.substring(end)
        val hashAt = url.indexOf('#')
        val beforeHash = if (hashAt < 0) url else url.substring(0, hashAt)
        val fragment = if (hashAt < 0) "" else url.substring(hashAt)
        val q = beforeHash.indexOf('?')
        if (q < 0) return link
        val base = beforeHash.substring(0, q)
        val host = hostOf(base)
        val params = beforeHash.substring(q + 1).split('&')
        val kept = params.filterNot { it.isNotEmpty() && isTracking(it.substringBefore('=').lowercase(), host) }
        if (kept.size == params.size) return link
        val query = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        return base + query + fragment + tail
    }

    private fun isTracking(name: String, host: String): Boolean {
        if (name.startsWith("utm_") || name in EVERYWHERE) return true
        return SITES.any { (site, names) -> name in names && (host == site || host.endsWith(".$site")) }
    }

    /** Lowercase host of `scheme://user@host:port/path`, without the user and the port. */
    private fun hostOf(base: String): String {
        val afterScheme = base.substringAfter("://", "")
        val authority = afterScheme.substringBefore('/')
        return authority.substringAfterLast('@').substringBefore(':').lowercase()
    }

    private val LINK = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    private const val TRAILING_PROSE = ".,;:!?)]}"

    /** Click ids and mail-campaign ids every site may carry: never part of a page's address. */
    private val EVERYWHERE = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid",
        "ttclid", "igshid", "igsh", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok", "_ga", "_gl",
        "oly_anon_id", "oly_enc_id", "vero_id", "wickedid", "rb_clickid", "s_cid",
    )

    /**
     * Share ids the large sites add to a copied link. Only on their own hosts: `si`, `s` and `t`
     * mean something else elsewhere, and on Twitter `t` is a share token but on YouTube a time.
     */
    private val SITES: List<Pair<String, Set<String>>> = listOf(
        "youtube.com" to setOf("si", "feature", "pp"),
        "youtu.be" to setOf("si", "feature"),
        "spotify.com" to setOf("si", "context"),
        "instagram.com" to setOf("img_index"),
        "x.com" to setOf("s", "t", "ref_src", "ref_url"),
        "twitter.com" to setOf("s", "t", "ref_src", "ref_url"),
        "facebook.com" to setOf("mibextid", "rdid", "sfnsn"),
        "tiktok.com" to setOf("is_from_webapp", "sender_device", "sender_web_id", "_r", "_t", "u_code"),
        "reddit.com" to setOf("share_id", "rdt", "ref", "ref_source"),
        "linkedin.com" to setOf("trk", "trackingid", "lipi", "rcm"),
        "threads.net" to setOf("xmt", "slof"),
    )
}
