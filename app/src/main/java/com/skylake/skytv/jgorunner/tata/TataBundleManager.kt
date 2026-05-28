package com.skylake.skytv.jgorunner.tata

import android.content.Context
import com.skylake.skytv.jgorunner.data.SkySharedPref
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal object TataBundleManager {
    data class BundleResult(
        val rootDir: File,
        val updated: Boolean
    )
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun ensureBundleReady(
        context: Context,
        forceUpdate: Boolean = false,
        log: (String) -> Unit = {}
    ): BundleResult {
        val pref = SkySharedPref.getInstance(context)
        val prefs = pref.myPrefs
        val now = System.currentTimeMillis()
        val rootDir = File(context.filesDir, TataConstants.ROOT_DIR_NAME)
        migrateLegacyState(context, rootDir, log)
        val shouldCheck = forceUpdate ||
            now - prefs.tataLastUpdateCheck >= TataConstants.UPDATE_CHECK_INTERVAL_MS ||
            !rootDir.exists()

        if (!shouldCheck) {
            return BundleResult(rootDir, false)
        }

        log("Tata bundle check started")

        var etag: String? = null
        var lastModified: String? = null
        try {
            val headRequest = Request.Builder()
                .url(TataConstants.ZIP_URL)
                .head()
                .build()
            httpClient.newCall(headRequest).execute().use { response ->
                etag = response.header("ETag")
                lastModified = response.header("Last-Modified")
            }
        } catch (e: Exception) {
            log("Tata bundle head check failed: ${e.message}")
        }

        val updateNeeded = forceUpdate ||
            !rootDir.exists() ||
            (etag != null && etag != prefs.tataBundleEtag) ||
            (lastModified != null && lastModified != prefs.tataBundleLastModified)

        if (updateNeeded) {
            log("Updating Tata bundle...")
            downloadAndExtract(context, rootDir, log)
            prefs.tataBundleEtag = etag
            prefs.tataBundleLastModified = lastModified
            prefs.tataBundleUpdatedAt = now
        } else {
            log("Tata bundle is up to date")
        }

        prefs.tataLastUpdateCheck = now
        pref.savePreferences()
        return BundleResult(rootDir, updateNeeded)
    }

    private fun migrateLegacyState(context: Context, rootDir: File, log: (String) -> Unit) {
        val legacyDir = File(rootDir, "app/data")
        val stateDir = File(context.filesDir, TataConstants.STATE_DIR_NAME)
        if (!legacyDir.exists() || !legacyDir.isDirectory) return
        if (stateDir.exists() && stateDir.listFiles()?.isNotEmpty() == true) return

        try {
            stateDir.mkdirs()
            legacyDir.listFiles()?.forEach { file ->
                val target = File(stateDir, file.name)
                if (file.isDirectory) {
                    file.copyRecursively(target, overwrite = true)
                } else {
                    file.copyTo(target, overwrite = true)
                }
            }
            log("Migrated Tata login state to persistent storage")
        } catch (e: Exception) {
            log("Tata state migration skipped: ${e.message}")
        }
    }

    private fun downloadAndExtract(context: Context, rootDir: File, log: (String) -> Unit) {
        val zipFile = File(context.cacheDir, TataConstants.ZIP_FILE_NAME)
        if (zipFile.exists()) {
            zipFile.delete()
        }

        val request = Request.Builder()
            .url(TataConstants.ZIP_URL)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to download Tata bundle: HTTP ${response.code}")
            }
            response.body?.byteStream()?.use { input ->
                FileOutputStream(zipFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Tata bundle response body empty")
        }

        val tempDir = File(context.filesDir, "${TataConstants.ROOT_DIR_NAME}_tmp_${System.currentTimeMillis()}")
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
        tempDir.mkdirs()

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val zipEntry = entry ?: continue
                val targetFile = File(tempDir, zipEntry.name)
                val canonicalTarget = targetFile.canonicalPath
                if (!canonicalTarget.startsWith(tempDir.canonicalPath)) {
                    throw IllegalStateException("Zip entry outside target dir: ${zipEntry.name}")
                }
                if (zipEntry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    FileOutputStream(targetFile).use { out ->
                        zis.copyTo(out)
                    }
                }
                zis.closeEntry()
            }
        }

        val extractedRoot = tempDir.listFiles()?.firstOrNull { it.isDirectory } ?: tempDir

        if (rootDir.exists()) {
            rootDir.deleteRecursively()
        }
        if (!extractedRoot.renameTo(rootDir)) {
            copyRecursive(extractedRoot, rootDir)
            extractedRoot.deleteRecursively()
        }
        tempDir.deleteRecursively()
        zipFile.delete()
        log("Tata bundle updated at ${rootDir.absolutePath}")
    }

    private fun copyRecursive(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists()) target.mkdirs()
            source.listFiles()?.forEach { child ->
                copyRecursive(child, File(target, child.name))
            }
        } else {
            target.parentFile?.mkdirs()
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
