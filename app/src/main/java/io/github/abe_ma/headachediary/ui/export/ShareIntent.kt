package io.github.abe_ma.headachediary.ui.export

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/**
 * FR-402: 生成したファイルを OS の共有シートで渡すための Intent。
 * アプリ自身は外部へ送信しない。共有先はユーザーが選ぶ。読み取り権限は一時的に付与する。
 */
fun buildShareIntent(uri: Uri, kind: ExportKind, chooserTitle: CharSequence): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = when (kind) {
            ExportKind.CSV -> "text/csv"
            ExportKind.PDF -> "application/pdf"
        }
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri) // 共有先に読み取り権限を渡すために必要
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, chooserTitle).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
