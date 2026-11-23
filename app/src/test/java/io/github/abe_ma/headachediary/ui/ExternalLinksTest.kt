package io.github.abe_ma.headachediary.ui

import android.content.ActivityNotFoundException
import androidx.compose.ui.platform.UriHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLinksTest {
    @Test
    fun FR106_ブラウザが無く開けなくても例外を出さずfalseを返す() {
        val handler = object : UriHandler {
            override fun openUri(uri: String) = throw ActivityNotFoundException("no browser")
        }
        assertFalse(handler.openUriSafely("https://open-meteo.com/"))
    }

    @Test
    fun FR106_開けたときはtrueを返し同じURLを渡す() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        assertTrue(handler.openUriSafely("https://open-meteo.com/"))
        assertEquals(listOf("https://open-meteo.com/"), opened)
    }
}
