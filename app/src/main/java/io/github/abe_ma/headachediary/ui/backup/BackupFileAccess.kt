package io.github.abe_ma.headachediary.ui.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
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

    /**
     * 作成の途中で失敗したとき、保存先に中途半端なファイルが残らないよう、作成した文書を削除する (T-21d)。
     * 削除できなくても例外は投げない (呼び出し側は失敗を表示して続行する)。
     * 戻り値: 削除できたか。false なら保存先に書きかけのファイルが残っている可能性がある。
     */
    fun deleteQuietly(uri: Uri): Boolean
}

class ContentResolverBackupFileAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackupFileAccess {
    override fun openInput(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open $uri")

    // "wt": 既存ファイルを上書きするときに古い内容が残らないよう切り詰める。保存先 (ドキュメントプロバイダ) によっては
    // "wt" を受け付けず例外になるので、そのときは "w" でやり直す (作成直後の新しい文書なので切り詰めは不要)。
    override fun openOutput(uri: Uri): OutputStream {
        val resolver = context.contentResolver
        return try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            if (e is SecurityException) throw e // 権限がないときは再試行しても同じ
            resolver.openOutputStream(uri, "w")
        } ?: throw FileNotFoundException("cannot open $uri")
    }

    override fun deleteQuietly(uri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            // 保存先の名前や Uri は健康データの手がかりになりうるので、ログには例外クラス名だけを残す。
            Log.w(TAG, "could not delete incomplete backup: ${e.javaClass.simpleName}")
            false
        }
    }

    private companion object {
        const val TAG = "BackupFileAccess"
    }
}
