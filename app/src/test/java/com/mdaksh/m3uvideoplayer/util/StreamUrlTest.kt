package com.mdaksh.m3uvideoplayer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamUrlTest {

    @Test
    fun `plain url without pipe is returned unchanged`() {
        val p = StreamUrl.parse("http://host/live.m3u8")
        assertEquals("http://host/live.m3u8", p.url)
        assertNull(p.referrer)
        assertNull(p.userAgent)
    }

    @Test
    fun `referer and user-agent are split off`() {
        val p = StreamUrl.parse("http://host/live.m3u8|Referer=https://site.com/&User-Agent=Mozilla/5.0")
        assertEquals("http://host/live.m3u8", p.url)
        assertEquals("https://site.com/", p.referrer)
        assertEquals("Mozilla/5.0", p.userAgent)
    }

    @Test
    fun `keys are case-insensitive and order independent`() {
        val p = StreamUrl.parse("http://host/s|user-agent=VLC/3.0|REFERER=http://ref/")
        assertEquals("http://host/s", p.url)
        assertEquals("http://ref/", p.referrer)
        assertEquals("VLC/3.0", p.userAgent)
    }

    @Test
    fun `only user-agent present`() {
        val p = StreamUrl.parse("http://host/s|User-Agent=SmartTV")
        assertEquals("http://host/s", p.url)
        assertNull(p.referrer)
        assertEquals("SmartTV", p.userAgent)
    }

    @Test
    fun `value containing equals sign survives`() {
        val p = StreamUrl.parse("http://host/s|Referer=https://site.com/play?token=abc123")
        assertEquals("https://site.com/play?token=abc123", p.referrer)
    }

    @Test
    fun `unknown header keys are ignored`() {
        val p = StreamUrl.parse("http://host/s|Origin=https://x/&Cookie=a=b&User-Agent=UA")
        assertEquals("http://host/s", p.url)
        assertNull(p.referrer)
        assertEquals("UA", p.userAgent)
    }

    @Test
    fun `referrer spelling variant is accepted`() {
        val p = StreamUrl.parse("http://host/s|Referrer=http://ref/")
        assertEquals("http://ref/", p.referrer)
    }

    @Test
    fun `empty header values are dropped`() {
        val p = StreamUrl.parse("http://host/s|Referer=&User-Agent=UA")
        assertNull(p.referrer)
        assertEquals("UA", p.userAgent)
    }
}
