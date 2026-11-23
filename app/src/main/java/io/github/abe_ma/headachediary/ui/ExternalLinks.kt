package io.github.abe_ma.headachediary.ui

import androidx.compose.ui.platform.UriHandler

/**
 * 外部リンクを開く。ブラウザが無い端末などで開けなくてもアプリは落とさない (開けなければ何もしない)。
 * 戻り値は開けたかどうか。
 */
fun UriHandler.openUriSafely(url: String): Boolean = runCatching { openUri(url) }.isSuccess
