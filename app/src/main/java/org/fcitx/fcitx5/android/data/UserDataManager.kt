/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.versionCodeCompat
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object UserDataManager {

    private val json = Json { prettyPrint = true }

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long
    )

    /**
     * Suffixes appended to the application id by the different build variants
     * (`.debug`, `.scbh`, ...). User data exported by any of them is importable by the
     * others, so the check below compares the *family* name instead of the exact id.
     */
    private val variantSuffixes = setOf("debug", "scbh", "dev", "release", "nightly", "beta")

    internal fun basePackageName(packageName: String): String {
        val idx = packageName.lastIndexOf('.')
        if (idx <= 0) return packageName
        return if (packageName.substring(idx + 1).lowercase() in variantSuffixes) {
            packageName.substring(0, idx)
        } else {
            packageName
        }
    }

    /**
     * `-shm` files are ephemeral shared-memory indexes and must not be carried around:
     * a stale one can make SQLite bail out while the `-wal` next to it is perfectly fine.
     */
    private fun isEphemeralDbFile(name: String) = name.endsWith("-shm")

    private fun writeFileTree(srcDir: File, destPrefix: String, dest: ZipOutputStream) {
        dest.putNextEntry(ZipEntry("$destPrefix/"))
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "") {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}/"))
                } else if (f.isFile && !isEphemeralDbFile(f.name)) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }

    private val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
    private val dataBasesDir = File(appContext.applicationInfo.dataDir, "databases")
    private val externalDir = appContext.getExternalFilesDir(null)!!
    private val recentlyUsedDir = appContext.filesDir.resolve(RecentlyUsed.DIR_NAME)

    /**
     * Name of the default [android.content.SharedPreferences] file of *this* build.
     * The name is derived from the application id, so it changes together with the
     * package name of the variant.
     */
    private val preferencesFileName = "${BuildConfig.APPLICATION_ID}_preferences.xml"

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis()) = runCatching {
        // flush pending writes so that the copied database files are self-contained
        runCatching { ClipboardManager.checkpointDatabase() }
            .onFailure { Timber.w(it, "Failed to checkpoint clipboard database") }
        ZipOutputStream(dest.buffered()).use { zipStream ->
            // shared_prefs
            writeFileTree(sharedPrefsDir, "shared_prefs", zipStream)
            // databases
            writeFileTree(dataBasesDir, "databases", zipStream)
            // external
            writeFileTree(externalDir, "external", zipStream)
            // recently_used moved to SharedPreference and shoud not be exported
            // metadata
            zipStream.putNextEntry(ZipEntry("metadata.json"))
            val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val metadata = Metadata(
                pkgInfo.packageName,
                pkgInfo.versionCodeCompat,
                Const.versionName,
                timestamp
            )
            json.encodeToStream(metadata, zipStream)
            zipStream.closeEntry()
        }
    }

    private fun copyDir(source: File, target: File) {
        val exists = source.exists()
        val isDir = source.isDirectory
        if (exists && isDir) {
            target.mkdirs()
            source.copyRecursively(target, overwrite = true)
        } else {
            Timber.w("Cannot import user data: path='${source.path}', exists=$exists, isDir=$isDir")
        }
    }

    /**
     * SharedPreferences are stored in a file named after the application id. A backup
     * created by another variant (e.g. the official release, or an older `.debug` build)
     * therefore carries a file this build would never look at. Rename it inside the
     * extracted directory before it is copied over.
     */
    private fun adoptSharedPrefs(tempDir: File, backupPackageName: String) {
        val dir = File(tempDir, "shared_prefs")
        if (!dir.isDirectory) return
        val candidates = dir.listFiles { f ->
            f.isFile && f.name.endsWith("_preferences.xml") && f.name != preferencesFileName
        } ?: return
        // prefer the one that belongs to the package the backup was made with
        val source = candidates.firstOrNull {
            it.name == "${backupPackageName}_preferences.xml"
        } ?: candidates.singleOrNull() ?: candidates.first()
        val dest = File(dir, preferencesFileName)
        if (source.renameTo(dest)) {
            Timber.d("Import user data: ${source.name} -> ${dest.name}")
        } else {
            Timber.w("Import user data: failed to rename ${source.name}")
        }
        candidates.forEach { stale ->
            if (stale !== source && stale.exists()) stale.delete()
        }
    }

    /** drop stale `-shm` files so that SQLite rebuilds them from the imported `-wal` */
    private fun dropEphemeralDbFiles(dir: File) {
        dir.listFiles { f -> f.isFile && isEphemeralDbFile(f.name) }?.forEach {
            if (!it.delete()) Timber.w("Import user data: failed to delete ${it.name}")
        }
    }

    fun import(src: InputStream) = runCatching {
        ZipInputStream(src).use { zipStream ->
            withTempDir { tempDir ->
                val extracted = zipStream.extract(tempDir)
                val metadataFile = extracted.find { it.name == "metadata.json" }
                    ?: errorRuntime(R.string.exception_user_data_metadata)
                val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
                if (basePackageName(metadata.packageName) != basePackageName(BuildConfig.APPLICATION_ID))
                    errorRuntime(R.string.exception_user_data_package_name_mismatch)
                adoptSharedPrefs(tempDir, metadata.packageName)
                copyDir(File(tempDir, "shared_prefs"), sharedPrefsDir)
                copyDir(File(tempDir, "databases"), dataBasesDir)
                // the databases of this installation have just been replaced: make sure
                // no stale journal of the *old* data survives the swap
                dropEphemeralDbFiles(dataBasesDir)
                copyDir(File(tempDir, "external"), externalDir)
                // keep importing recently_used for backwords compatibility
                copyDir(File(tempDir, "recently_used"), recentlyUsedDir)
                metadata
            }
        }
    }
}
