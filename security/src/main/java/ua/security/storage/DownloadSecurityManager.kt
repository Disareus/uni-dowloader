package ua.security.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.regex.Pattern

class DownloadSecurityManager(context: Context) {

    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "DownloadSecurityManager"
        private val ILLEGAL_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]")
        private const val MAX_FILENAME_LENGTH = 120
        const val SUBDIRECTORY_NAME = "AniUA"

        @Volatile
        private var sInstance: DownloadSecurityManager? = null

        @JvmStatic
        fun getInstance(context: Context): DownloadSecurityManager {
            return sInstance ?: synchronized(DownloadSecurityManager::class.java) {
                sInstance ?: DownloadSecurityManager(context).also { sInstance = it }
            }
        }

        @JvmStatic
        fun sanitizeFilename(rawTitle: String, fallback: String = "video"): String {
            val normalized = Normalizer.normalize(rawTitle, Normalizer.Form.NFKC)
            var clean = ILLEGAL_FILENAME_CHARS.matcher(normalized).replaceAll("_")
            clean = clean.replace(Regex("\\s+"), " ").trim(' ', '.', '_')
            if (clean.length > MAX_FILENAME_LENGTH) {
                clean = clean.substring(0, MAX_FILENAME_LENGTH).trimEnd(' ', '.', '_')
            }
            return if (clean.isEmpty()) fallback else clean
        }
    }

    fun sanitizeFileName(rawTitle: String): String = sanitizeFilename(rawTitle, "video")

    fun getGuardedStorageDir(subFolder: String? = null): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "movies")
        val aniUaDir = File(baseDir, SUBDIRECTORY_NAME)
        val targetDir = if (!subFolder.isNullOrEmpty()) {
            val safeSub = sanitizeFilename(subFolder, "")
            if (safeSub.isNotEmpty()) File(aniUaDir, safeSub) else aniUaDir
        } else {
            aniUaDir
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return targetDir
    }

    @Throws(SecurityException::class, IOException::class)
    fun validateCanonicalDestination(targetFile: File, baseDir: File) {
        val canonicalBase = baseDir.canonicalPath
        val canonicalTarget = targetFile.canonicalPath
        if (!canonicalTarget.startsWith(canonicalBase + File.separator)) {
            throw SecurityException("Security violation: Path Traversal attempt detected!")
        }
    }

    fun createScopedVideoUri(fileName: String, mimeType: String, subFolder: String? = null): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        val safeSub = if (!subFolder.isNullOrEmpty()) {
            val sanitized = sanitizeFilename(subFolder, "")
            if (sanitized.isNotEmpty()) "$SUBDIRECTORY_NAME/$sanitized" else SUBDIRECTORY_NAME
        } else {
            SUBDIRECTORY_NAME
        }
        val relativePath = Environment.DIRECTORY_MOVIES + "/" + safeSub

        val resolver: ContentResolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.insert(collection, contentValues)
    }

    fun publishPendingVideo(contentUri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver: ContentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(contentUri, contentValues, null, null)
        }
    }
}
