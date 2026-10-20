package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Link cleanup (#26): tracking parameters out, everything else as it was written. The links are
 * the shapes the large sites put on a copied link.
 */
class LinkCleanerTest {

    @Test
    fun campaignAndClickIdsGoEverywhere() {
        assertEquals(
            "https://example.com/post?id=7",
            LinkCleaner.cleanUrl("https://example.com/post?utm_source=feed&id=7&utm_medium=social&fbclid=IwAR0x"),
        )
        assertEquals("https://shop.example/item", LinkCleaner.cleanUrl("https://shop.example/item?gclid=abc&msclkid=def"))
    }

    @Test
    fun sharedVideoAndMusicLinksKeepWhatTheyPointAt() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42",
            LinkCleaner.cleanUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=AbCdEf123&t=42"),
        )
        assertEquals("https://youtu.be/dQw4w9WgXcQ", LinkCleaner.cleanUrl("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf123"))
        assertEquals(
            "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC",
            LinkCleaner.cleanUrl("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=1a2b3c4d"),
        )
        assertEquals(
            "https://www.instagram.com/p/C1x2y3z/",
            LinkCleaner.cleanUrl("https://www.instagram.com/p/C1x2y3z/?igsh=MWQ1ZGUxMzBkMA=="),
        )
        assertEquals("https://x.com/user/status/123", LinkCleaner.cleanUrl("https://x.com/user/status/123?s=20&t=AbC"))
    }

    @Test
    fun aSitesShareIdIsLeftAloneOnOtherSites() {
        // `t` is a time on YouTube and `s` a search elsewhere: only the site that uses them for
        // tracking loses them.
        val search = "https://example.org/find?s=kinetica&t=1"
        assertEquals(search, LinkCleaner.cleanUrl(search))
        val notYoutube = "https://notyoutube.com/watch?si=keep"
        assertEquals(notYoutube, LinkCleaner.cleanUrl(notYoutube))
    }

    @Test
    fun aCleanLinkIsReturnedAsWritten() {
        for (url in listOf(
            "https://example.com",
            "https://example.com/a/b?q=two%20words&lang=it",
            "http://example.com/?",
            "https://example.com/page#section",
            "https://example.com/a?x=1&&y=2",
        )) {
            assertEquals(url, LinkCleaner.cleanUrl(url))
        }
    }

    @Test
    fun theFragmentAndTheProseAroundTheLinkStay() {
        assertEquals(
            "https://example.com/doc?id=3#part-2",
            LinkCleaner.cleanUrl("https://example.com/doc?id=3&utm_campaign=x#part-2"),
        )
        assertEquals(
            "Read this (https://example.com/a). Then https://example.org/b, ok",
            LinkCleaner.clean(
                "Read this (https://example.com/a?utm_source=x). Then https://example.org/b?fbclid=y, ok",
            ),
        )
    }

    @Test
    fun parameterNamesMatchWhateverTheirCase() {
        assertEquals("https://example.com/a", LinkCleaner.cleanUrl("https://example.com/a?UTM_Source=x&FBCLID=y"))
        assertEquals("HTTPS://example.com/a", LinkCleaner.cleanUrl("HTTPS://example.com/a?utm_source=x"))
    }

    @Test
    fun theLinkJustPastedIsTheOneCleaned() {
        val before = "look at https://youtu.be/abc?si=xyz"
        val r = LinkCleaner.cleanLastLink(before)!!
        assertEquals("https://youtu.be/abc?si=xyz".length, r.span)
        assertEquals("https://youtu.be/abc", r.text)
        // A space typed after it is kept where it was.
        val spaced = LinkCleaner.cleanLastLink("$before ")!!
        assertEquals("https://youtu.be/abc?si=xyz ".length, spaced.span)
        assertEquals("https://youtu.be/abc ", spaced.text)
    }

    @Test
    fun nothingToDoIsNull() {
        assertNull(LinkCleaner.cleanLastLink("just words"))
        assertNull(LinkCleaner.cleanLastLink("https://example.com/clean"))
        assertNull(LinkCleaner.cleanLastLink(""))
        assertNull(LinkCleaner.cleanLastLink("https://youtu.be/abc?si=xyz and then words"))
    }
}
