package com.muddassir.clearview.todo.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * The reader's own alarm sounds.
 *
 * A todo may ring with an audio file picked off the device instead of the
 * system alarm tone. The file is referenced by a persisted `content://` URI, so
 * what the editor has to be able to do is name it: "Rain on a tin roof.mp3" is
 * something the reader recognizes, and the raw URI is not.
 */
object AlarmSounds {

    /**
     * The display name of the audio file behind [uri], or null when there is
     * none to show (nothing picked, or the file can no longer be read — a
     * deleted file or a revoked permission).
     *
     * The null answer is deliberately NOT an error the caller has to handle
     * differently: a sound that cannot be named is a sound the alarm service
     * will fall back to the system tone for, so the editor shows the fallback
     * too rather than a stale filename.
     */
    fun displayName(context: Context, uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) {
                        cursor.getString(index)?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }

    /**
     * A short, readable label for [uri]: the file's own name when the provider
     * reports one, otherwise the last path segment, otherwise null.
     *
     * Some providers return no display name at all (a plain file path picked
     * through a file manager); without the fallback those picks would show the
     * generic "Device audio" label even though the app knows perfectly well
     * which file it is.
     */
    fun shortLabel(context: Context, uri: String?): String? =
        displayName(context, uri)
            ?: uri?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}
