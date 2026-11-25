package io.github.abe_ma.headachediary.ui.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * SAF で選ばれた Uri の入出力 (FR-601, 603)。ContentResolver を ViewModel から切り離してテストできるようにする。
 * 呼び出し側 (ViewModel) が Dispatchers.IO で呼び、`use {}` で閉じる。
 */
interface BackupFileAccess {
    fun openInput(uri: Uri): InputStream
    fun openOutput(uri: Uri): OutputStream
}

class ContentResolverBackupFileAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackupFileAccess {
    override fun openInput(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open $uri")

    // "wt": 既存ファイルを上書きするときに古い内容が残らないよう切り詰める。
    override fun openOutput(uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("cannot open $uri")
}
