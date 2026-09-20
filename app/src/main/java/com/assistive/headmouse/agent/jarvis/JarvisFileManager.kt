package com.assistive.headmouse.agent.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val formattedSize: String,
    val lastModified: String
)

data class StorageOverview(
    val totalSpaceGb: String,
    val freeSpaceGb: String,
    val usedSpaceGb: String,
    val quickFolders: List<String>
)

/**
 * Native File System Commander for J.A.R.V.I.S.
 * Allows autonomous exploration, querying, and managing of the Android device storage
 * without third-party app dependencies or restrictions.
 */
class JarvisFileManager(private val context: Context) {

    val rootStorage: File = Environment.getExternalStorageDirectory() ?: File("/sdcard")

    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Resolves user-friendly paths like "Downloads", "DCIM", "Documents" to absolute paths.
     */
    fun resolvePath(rawPath: String): File {
        val trimmed = rawPath.trim().removePrefix("/").removePrefix("storage/emulated/0/")
        if (trimmed.isEmpty() || trimmed == "." || trimmed.equals("root", ignoreCase = true) || trimmed.equals("home", ignoreCase = true)) {
            return rootStorage
        }

        val lower = trimmed.lowercase()
        val standardDir = when {
            lower.contains("download") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            lower.contains("dcim") || lower.contains("camera") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            lower.contains("document") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            lower.contains("picture") || lower.contains("photo") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            lower.contains("movie") || lower.contains("video") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            lower.contains("music") || lower.contains("audio") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            lower.contains("whatsapp") -> File(rootStorage, "Android/media/com.whatsapp/WhatsApp/Media")
            else -> File(rootStorage, trimmed)
        }

        return if (standardDir.exists()) standardDir else File(rootStorage, trimmed)
    }

    /**
     * Lists directory contents up to limit items.
     */
    fun listDirectory(folderQuery: String = "", limit: Int = 30): List<FileItem> {
        val targetDir = resolvePath(folderQuery)
        if (!targetDir.exists() || !targetDir.isDirectory) {
            Log.w(TAG, "Target directory does not exist: ${targetDir.absolutePath}")
            return emptyList()
        }

        val files = targetDir.listFiles() ?: return emptyList()
        val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

        return files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .take(limit)
            .map { file ->
                val sizeBytes = if (file.isDirectory) 0L else file.length()
                FileItem(
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = file.isDirectory,
                    sizeBytes = sizeBytes,
                    formattedSize = if (file.isDirectory) "Folder" else formatSize(sizeBytes),
                    lastModified = dateFormat.format(Date(file.lastModified()))
                )
            }
    }

    /**
     * Searches for files by keyword/extension across internal storage.
     */
    fun searchFiles(query: String, maxResults: Int = 20): List<FileItem> {
        val cleanQuery = query.lowercase().trim().removePrefix("*.")
        val results = mutableListOf<FileItem>()
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

        fun traverse(dir: File, currentDepth: Int) {
            if (currentDepth > 4 || results.size >= maxResults) return
            val list = dir.listFiles() ?: return

            for (f in list) {
                if (results.size >= maxResults) break
                // Skip hidden and system cache dirs
                if (f.name.startsWith(".") || f.name == "cache") continue

                if (f.name.lowercase().contains(cleanQuery)) {
                    val sizeBytes = if (f.isDirectory) 0L else fileLength(f)
                    results.add(
                        FileItem(
                            name = f.name,
                            path = f.absolutePath,
                            isDirectory = f.isDirectory,
                            sizeBytes = sizeBytes,
                            formattedSize = if (f.isDirectory) "Folder" else formatSize(sizeBytes),
                            lastModified = dateFormat.format(Date(f.lastModified()))
                        )
                    )
                }

                if (f.isDirectory && !f.name.equals("Android", ignoreCase = true)) {
                    traverse(f, currentDepth + 1)
                }
            }
        }

        traverse(rootStorage, 0)
        return results
    }

    private fun fileLength(file: File): Long {
        return try { file.length() } catch (_: Exception) { 0L }
    }

    /**
     * Deletes a file or directory recursively.
     */
    fun deleteFileOrDirectory(targetQuery: String): Boolean {
        val target = resolvePath(targetQuery)
        if (!target.exists()) {
            val matched = searchFiles(targetQuery, 1).firstOrNull() ?: return false
            val matchedFile = File(matched.path)
            return matchedFile.deleteRecursively()
        }
        return target.deleteRecursively()
    }

    /**
     * Opens a file in the user's default registered viewer app.
     */
    fun openFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists()) return false

            val ext = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                } catch (e: Exception) {
                    Uri.fromFile(file)
                }
            } else {
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file: $path", e)
            false
        }
    }

    /**
     * Returns total & available storage information.
     */
    fun getStorageOverview(): StorageOverview {
        val total = rootStorage.totalSpace
        val free = rootStorage.freeSpace
        val used = total - free

        val quickDirs = listOf("Download", "DCIM", "Documents", "Pictures", "WhatsApp")
            .filter { resolvePath(it).exists() }

        return StorageOverview(
            totalSpaceGb = String.format(Locale.US, "%.1f GB", total / (1024.0 * 1024.0 * 1024.0)),
            freeSpaceGb = String.format(Locale.US, "%.1f GB", free / (1024.0 * 1024.0 * 1024.0)),
            usedSpaceGb = String.format(Locale.US, "%.1f GB", used / (1024.0 * 1024.0 * 1024.0)),
            quickFolders = quickDirs
        )
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    companion object {
        private const val TAG = "JarvisFileManager"
    }
}
