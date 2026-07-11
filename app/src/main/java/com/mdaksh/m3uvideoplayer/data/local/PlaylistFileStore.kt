package com.mdaksh.m3uvideoplayer.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Result of importing a picked playlist file: a stable local source url plus the original name. */
data class ImportedFile(val url: String, val displayName: String)

/**
 * Copies a user-picked playlist file (a transient `content://` [Uri]) into app-private storage and
 * hands back a durable `file://` url. Storing the copy — rather than the original Uri — means the
 * source survives the picker's short-lived read permission, and the existing
 * `URL(playlist.url).openStream()` sync path re-reads it on every scheduled refresh unchanged.
 */
@Singleton
class PlaylistFileStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun importToLocal(uri: Uri): ImportedFile = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, PLAYLIST_DIR).apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.m3u")

        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Unable to read the selected file")

        ImportedFile(
            url = Uri.fromFile(target).toString(),
            displayName = queryDisplayName(uri) ?: target.name
        )
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }

    private companion object {
        const val PLAYLIST_DIR = "playlists"
    }
}
