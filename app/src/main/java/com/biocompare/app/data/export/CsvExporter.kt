package com.biocompare.app.data.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.biocompare.app.data.db.SampleEntity
import com.biocompare.app.data.repository.BioCompareRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a session's full sample log to a CSV in the user's Downloads folder.
 * Uses MediaStore on Android 10+ (proper scoped-storage path) and the legacy
 * Environment API on older devices.
 *
 * The CSV is human-readable and Excel-importable: one row per sample, with
 * the meaning of v1..v5 inferred from the [type] column.
 */
@Singleton
class CsvExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BioCompareRepository,
) {
    /**
     * Returns the user-visible relative path of the exported file (for a
     * "Saved to Downloads/biocompare/..." toast), or null on failure.
     */
    suspend fun export(sessionId: Long): String? = withContext(Dispatchers.IO) {
        val session = repository.getSession(sessionId) ?: return@withContext null
        val samples = repository.getAllSamples(sessionId)
        val filename = "biocompare_session_${sessionId}_${session.startedAtMs}.csv"

        val out = openOutput(filename) ?: return@withContext null
        runCatching {
            out.bufferedWriter(Charsets.UTF_8).use { w ->
                writeHeader(w)
                for (s in samples) writeRow(w, s)
            }
        }.onFailure {
            Log.w(TAG, "CSV export failed", it)
            return@withContext null
        }
        return@withContext "Downloads/biocompare/$filename"
    }

    private fun openOutput(filename: String): OutputStream? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/biocompare")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "biocompare")
            if (!dir.exists()) dir.mkdirs()
            FileOutputStream(File(dir, filename))
        }
    }

    private fun writeHeader(w: java.io.BufferedWriter) {
        w.write("timestamp_ms,device_source,sample_type,v1,v2,v3,v4,v5,i_value\n")
    }

    private fun writeRow(w: java.io.BufferedWriter, s: SampleEntity) {
        w.write(s.timestampMs.toString()); w.write(",")
        w.write(s.deviceSource); w.write(",")
        w.write(s.type); w.write(",")
        w.write(s.v1?.toString() ?: ""); w.write(",")
        w.write(s.v2?.toString() ?: ""); w.write(",")
        w.write(s.v3?.toString() ?: ""); w.write(",")
        w.write(s.v4?.toString() ?: ""); w.write(",")
        w.write(s.v5?.toString() ?: ""); w.write(",")
        w.write(s.iValue?.toString() ?: ""); w.write("\n")
    }

    companion object {
        private const val TAG = "CsvExporter"
    }
}
