package com.xmsender.blunlock

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Обновление из GitHub Releases (тег latest, имя релиза «build N»). */
object Updater {

    private const val API = "https://api.github.com/repos/sapog3090/Auto-sender-Xiaomi-community-/releases/tags/latest"
    private const val ASSET = "BlUnlockSender.apk"

    private data class Release(val build: Long, val url: String)

    private fun fetch(): Release? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return conn.inputStream.use { stream ->
            val json = JSONObject(stream.bufferedReader().readText())
            val build = Regex("\\d+").find(json.optString("name"))?.value?.toLongOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == ASSET }
                ?.optString("browser_download_url") ?: return null
            Release(build, url)
        }
    }

    private fun currentBuild(activity: Activity): Long =
        PackageInfoCompat.getLongVersionCode(activity.packageManager.getPackageInfo(activity.packageName, 0))

    /** silent = true — молчать, если обновлений нет или нет сети (проверка при запуске). */
    fun check(activity: Activity, silent: Boolean) {
        if (!silent) toast(activity, "Проверяю обновления…")
        thread {
            val release = runCatching { fetch() }.getOrNull()
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                when {
                    release == null -> if (!silent) toast(activity, "Не удалось проверить обновления")
                    release.build <= currentBuild(activity) ->
                        if (!silent) toast(activity, "У вас последняя версия (сборка ${currentBuild(activity)})")
                    else -> AlertDialog.Builder(activity)
                        .setTitle("Доступно обновление")
                        .setMessage("Сборка ${release.build} (у вас ${currentBuild(activity)}). Установить?")
                        .setPositiveButton("Обновить") { _, _ -> download(activity, release.url) }
                        .setNegativeButton("Позже", null)
                        .show()
                }
            }
        }
    }

    private fun download(activity: Activity, url: String) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            toast(activity, "Разрешите установку из этого приложения и нажмите «Обновить» ещё раз")
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        toast(activity, "Скачиваю…")
        thread {
            val file = File(activity.cacheDir, "update.apk")
            val ok = runCatching {
                (URL(url).openConnection() as HttpURLConnection).inputStream.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }.isSuccess
            activity.runOnUiThread {
                if (!ok) {
                    toast(activity, "Не удалось скачать обновление")
                    return@runOnUiThread
                }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }
    }

    private fun toast(activity: Activity, s: String) = Toast.makeText(activity, s, Toast.LENGTH_SHORT).show()
}
