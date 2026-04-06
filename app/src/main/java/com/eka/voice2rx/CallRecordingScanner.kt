package com.eka.voice2rx

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

class CallRecordingScanner(private val context: Context) {

    companion object {
        private const val TAG = "CallRecordingScanner"

        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "wav", "aac", "amr", "ogg", "3gp")
    }

    data class AudioFile(
        val path: String,
        val name: String,
        val dateModified: Long,
        val duration: Long
    )

    fun findLatestCallRecording(): AudioFile? {
        return findRecentCallRecordings(limit = 1).firstOrNull()
    }

    fun findRecentCallRecordings(limit: Int = 10): List<AudioFile> {
        val seenPaths = mutableSetOf<String>()
        val results = mutableListOf<AudioFile>()

        // 1. Query MediaStore for indexed audio files
        results.addAll(queryMediaStore(seenPaths))

        // 2. Directly scan known recording directories (MIUI etc. may not be indexed)
        results.addAll(scanFileSystem(seenPaths))

        // Sort all by date descending
        results.sortByDescending { it.dateModified }

        val limited = results.take(limit)
        Log.d(TAG, "Found ${results.size} total audio files, returning ${limited.size}")
        return limited
    }

    private fun queryMediaStore(seenPaths: MutableSet<String>): List<AudioFile> {
        val results = mutableListOf<AudioFile>()

        val uris = mutableListOf<Uri>(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.INTERNAL_CONTENT_URI
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uris.add(0, MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
        }

        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION
        )
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        for (uri in uris) {
            try {
                context.contentResolver.query(uri, projection, null, null, sortOrder)
                    ?.use { cursor ->
                        val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                        val nameCol =
                            cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                        val dateCol =
                            cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                        val durationCol =
                            cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                        while (cursor.moveToNext()) {
                            val path = cursor.getString(pathCol) ?: continue
                            if (!seenPaths.add(path)) continue
                            results.add(
                                AudioFile(
                                    path = path,
                                    name = cursor.getString(nameCol) ?: File(path).name,
                                    dateModified = cursor.getLong(dateCol),
                                    duration = cursor.getLong(durationCol)
                                )
                            )
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying $uri", e)
            }
        }
        return results
    }

    private fun scanFileSystem(seenPaths: MutableSet<String>): List<AudioFile> {
        val results = mutableListOf<AudioFile>()
        val root = Environment.getExternalStorageDirectory()

        // Scan the entire external storage recursively for audio files
        scanDirectory(root, seenPaths, results)

        return results
    }

    private fun scanDirectory(
        dir: File,
        seenPaths: MutableSet<String>,
        results: MutableList<AudioFile>
    ) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                // Skip Android system dirs to avoid noise
                if (file.name == "Android") continue
                scanDirectory(file, seenPaths, results)
            } else if (isAudioFile(file.name) && seenPaths.add(file.absolutePath)) {
                results.add(
                    AudioFile(
                        path = file.absolutePath,
                        name = file.name,
                        dateModified = file.lastModified() / 1000,
                        duration = getFileDuration(file)
                    )
                )
            }
        }
    }

    private fun getFileDuration(file: File): Long {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()
            duration
        } catch (e: Exception) {
            0L
        }
    }

    private fun isAudioFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }
}
