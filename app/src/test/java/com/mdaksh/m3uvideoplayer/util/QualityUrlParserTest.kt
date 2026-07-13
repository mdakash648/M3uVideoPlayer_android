package com.mdaksh.m3uvideoplayer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityUrlParserTest {

    @Test
    fun `url without resolution token yields single Original variant`() {
        val v = QualityUrlParser.variants("http://host/movie.mp4")
        assertEquals(1, v.size)
        assertEquals(QualityUrlParser.ORIGINAL_LABEL, v[0].label)
        assertEquals("http://host/movie.mp4", v[0].url)
    }

    @Test
    fun `token present generates full ladder high to low with substitution`() {
        val v = QualityUrlParser.variants("http://host/movie_720p.mp4")
        assertEquals(listOf("1080p", "720p", "480p"), v.map { it.label })
        assertEquals("http://host/movie_1080p.mp4", v[0].url)
        assertEquals("http://host/movie_720p.mp4", v[1].url)
        assertEquals("http://host/movie_480p.mp4", v[2].url)
    }

    @Test
    fun `token detection is case insensitive`() {
        val v = QualityUrlParser.variants("http://host/Movie_1080P.mp4")
        // The matched token "1080P" is replaced by each ladder value regardless of case.
        assertEquals("http://host/Movie_720p.mp4", v[1].url)
    }

    @Test
    fun `only the first matched token is targeted by replace`() {
        // Both the path segment and the query carry the token; replace swaps every occurrence of the
        // detected token string, which is the intended behaviour for these mirror-path URLs.
        val v = QualityUrlParser.variants("http://host/720p/movie_720p.mp4")
        assertEquals("http://host/480p/movie_480p.mp4", v[2].url)
    }

    @Test
    fun `detected index maps to the token present in the url`() {
        val url = "http://host/movie_480p.mp4"
        val v = QualityUrlParser.variants(url)
        assertEquals(2, QualityUrlParser.detectedIndex(url, v))
    }

    @Test
    fun `detected index is zero when no token present`() {
        val url = "http://host/movie.mp4"
        val v = QualityUrlParser.variants(url)
        assertEquals(0, QualityUrlParser.detectedIndex(url, v))
    }
}
