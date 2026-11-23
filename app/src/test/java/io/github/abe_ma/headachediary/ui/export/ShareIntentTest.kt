package io.github.abe_ma.headachediary.ui.export

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34]) // SDK 36 の Robolectric は Java 21 を要求するため (既存テストと同じ)
class ShareIntentTest {
    private val uri = Uri.parse("content://io.github.abe_ma.headachediary.fileprovider/exports/headache-diary.pdf")

    @Test
    fun FR402_PDFの共有はACTION_SENDで読み取り権限とclipDataを付ける() {
        val chooser = buildShareIntent(uri, ExportKind.PDF, "共有")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertNotNull(send.clipData)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun FR402_CSVの共有はtext_csv() {
        val send = buildShareIntent(uri, ExportKind.CSV, "共有").getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("text/csv", send.type)
    }
}
