package com.xmsender.blunlock

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Обмен сессии Mi аккаунта (cookie passToken с account.xiaomi.com) на new_bbs_serviceToken:
 * serviceLogin?sid=18n_bbs_global&_json=true → location → редиректы, где сервер ставит cookie.
 */
object TokenExchange {

    private const val SID = "18n_bbs_global"

    /** Блокирующий вызов. accountCookies — строка cookie для account.xiaomi.com. */
    fun fetch(accountCookies: String, log: (String) -> Unit): String? {
        val jar = object : CookieJar {
            val store = mutableListOf<Cookie>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                store += cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = store.filter { it.matches(url) }
        }
        val client = OkHttpClient.Builder()
            .cookieJar(jar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val first = Request.Builder()
            .url("https://account.xiaomi.com/pass/serviceLogin?sid=$SID&_json=true")
            .header("Cookie", accountCookies)
            .header("User-Agent", MiApi.USER_AGENT)
            .build()
        val location = client.newCall(first).execute().use { resp ->
            val body = resp.body?.string().orEmpty().removePrefix("&&&START&&&")
            val json = JSONObject(body)
            json.optString("location").ifBlank {
                log("serviceLogin: нет location (code=${json.opt("code")} ${json.optString("desc")})")
                return null
            }
        }

        client.newCall(
            Request.Builder().url(location).header("User-Agent", MiApi.USER_AGENT).build()
        ).execute().use { it.body?.string() }

        return jar.store.lastOrNull { it.name == "new_bbs_serviceToken" }?.value
            ?: jar.store.lastOrNull { it.name == "serviceToken" }?.value
            ?: run {
                log("Обмен токена: cookie не получены (${jar.store.map { it.name }})")
                null
            }
    }
}
