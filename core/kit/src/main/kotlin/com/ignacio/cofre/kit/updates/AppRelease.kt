package com.ignacio.cofre.kit.updates

import com.ignacio.cofre.kit.common.FileOps
import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.HttpFailure
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.await
import com.ignacio.cofre.kit.downloads.DiskPreflight
import com.ignacio.cofre.kit.downloads.DownloadFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * A Cofre release as the update feed describes it (`updates/latest.json` on main, written by `scripts/release.py`).
 * The feed isn't signed: trust comes from the release signing key, which the app checks on the APK before installing.
 */
@Serializable
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val tag: String,
    val apk: String,
    val size: Long,
    val sha256: String,
    val minSdk: Int,
    /** Plain text; newlines allowed. */
    val notes: String = "",
) {
    fun isNewerThan(code: Int): Boolean = versionCode > code

    /** Whether a device on API [sdk] can install it. */
    fun fits(sdk: Int): Boolean = sdk >= minSdk

    /**
     * Downloads the APK to [to], reporting the bytes so far. A file of the wrong size or SHA-256 fails with
     * [DownloadFailure.SizeMismatch] or [DownloadFailure.HashMismatch]; a failed or cancelled download leaves nothing.
     */
    suspend fun download(to: File, http: OkHttpClient = Kit.http, progress: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        to.parentFile?.mkdirs()
        val room = DiskPreflight.check(size, to)
        if (!room.hasRoom) throw DownloadFailure.NoDiskSpace(room.needBytes, room.freeBytes)
        val part = File("${to.path}.part")
        try {
            http.newCall(Request.Builder().url(apk).header("Accept-Encoding", "identity").build()).await().use { response ->
                if (!response.isSuccessful) throw DownloadFailure.Http(response.code)
                part.outputStream().use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            if (done > size) throw DownloadFailure.SizeMismatch
                            output.write(buffer, 0, read)
                            progress(done)
                        }
                    }
                }
            }
            if (part.length() != size) throw DownloadFailure.SizeMismatch
            if (Hashes.sha256(part) != sha256) throw DownloadFailure.HashMismatch
            FileOps.replace(part, to)
            to
        } finally {
            part.delete()
        }
    }

    companion object {
        /** The feed. A 404 means nothing is released yet. */
        const val FEED = "https://raw.githubusercontent.com/ignaciojuarez/cofre-android/main/updates/latest.json"

        private val SHA256 = Regex("[0-9a-f]{64}")

        /** The feed's JSON; refused when the APK isn't a GitHub release download or the checksum isn't SHA-256 hex. */
        fun parse(text: String): AppRelease = Kit.json.decodeFromString<AppRelease>(text).also {
            require(it.versionCode > 0 && it.size > 0 && it.minSdk > 0) { "The update feed is incomplete." }
            require(SHA256.matches(it.sha256)) { "The update feed has no valid SHA-256." }
            require(trusted(it.apk)) { "The update isn't a GitHub release download." }
        }

        /** HTTPS on GitHub's release downloads (or the host they're served from), nothing else. */
        fun trusted(apk: String): Boolean {
            val url = apk.toHttpUrlOrNull() ?: return false
            if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
            return when (url.host) {
                "github.com" -> url.pathSegments.size == 6 && url.pathSegments[2] == "releases" && url.pathSegments[3] == "download"
                "objects.githubusercontent.com" -> true
                else -> false
            }
        }

        /** The latest release, or null when the feed doesn't exist yet (404). */
        suspend fun fetch(url: String = FEED, http: OkHttpClient = Kit.http): AppRelease? = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).await().use {
                if (it.code == 404) return@withContext null
                if (!it.isSuccessful) throw HttpFailure(it.code)
                parse(it.body.string())
            }
        }
    }
}
