package com.xmsender.blunlock

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Вход в Mi аккаунт через официальную страницу Xiaomi в WebView.
 * Пароль приложение не видит: после входа берём cookie new_bbs_serviceToken,
 * а если её нет — обмениваем сессию аккаунта (passToken) на токен сообщества.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN = "token"
        private const val LOGIN_URL = "https://account.xiaomi.com/fe/service/login/password?_locale=en_IN&checkSafePhone=false&sid=18n_bbs_global&qs=%253Fcallback%253Dhttps%25253A%25252F%25252Fsgp-api.buy.mi.com%25252Fbbs%25252Fapi%25252Fglobal%25252Fuser%25252Flogin-back%25253Ffollowup%25253Dhttps%2525253A%2525252F%2525252Fnew-ams.c.mi.com%2525252Fglobal%2525252F%252526sign%25253DM2UyYmIxZjc0MGQxODhkYjg3NWVlNDI4ZGQxNzk3ZmY3MThhYTVmNA%25252C%25252C%2526sid%253D18n_bbs_global%2526_locale%253Den_IN%2526checkSafePhone%253Dfalse&callback=https%3A%2F%2Fsgp-api.buy.mi.com%2Fbbs%2Fapi%2Fglobal%2Fuser%2Flogin-back%3Ffollowup%3Dhttps%253A%252F%252Fnew-ams.c.mi.com%252Fglobal%252F%26sign%3DM2UyYmIxZjc0MGQxODhkYjg3NWVlNDI4ZGQxNzk3ZmY3MThhYTVmNA%2C%2C&_sign=%2BnjnarFZlvmk2A9UJro3U%2BS0lbc%3D&serviceParam=%7B%22checkSafePhone%22%3Afalse%2C%22checkSafeAddress%22%3Afalse%2C%22lsrp_score%22%3A0.0%7D&showActiveX=false&theme=&needTheme=false&bizDeviceType="
        private val COOKIE_URLS = listOf(
            "https://sgp-api.buy.mi.com",
            "https://buy.mi.com",
            "https://new-ams.c.mi.com",
            "https://new.c.mi.com",
            "https://c.mi.com",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    private var exchanging = false
    private var exchangeAttempts = 0

    private val poller = object : Runnable {
        override fun run() {
            checkCookies()
            if (!done) handler.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Вход в Mi аккаунт"

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        val web = WebView(this)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                checkCookies()
            }
        }
        setContentView(web)
        web.loadUrl(LOGIN_URL)
        handler.post(poller)
    }

    private fun checkCookies() {
        if (done) return
        val cm = CookieManager.getInstance()
        for (u in COOKIE_URLS) {
            val token = cookieValue(cm.getCookie(u), "new_bbs_serviceToken")
            if (!token.isNullOrBlank()) {
                finishWith(token)
                return
            }
        }
        val account = cm.getCookie("https://account.xiaomi.com")
        if (cookieValue(account, "passToken") != null) exchange(account!!)
    }

    private fun exchange(accountCookies: String) {
        if (exchanging || exchangeAttempts >= 3) return
        exchanging = true
        exchangeAttempts++
        lifecycleScope.launch {
            val token = try {
                withContext(Dispatchers.IO) { TokenExchange.fetch(accountCookies, Sender::log) }
            } catch (e: Exception) {
                Sender.log("Обмен токена: ${e.message}")
                null
            }
            exchanging = false
            if (!token.isNullOrBlank()) {
                finishWith(token)
            } else if (exchangeAttempts >= 3) {
                Toast.makeText(
                    this@LoginActivity,
                    "Не удалось получить токен. Подробности в логе на главном экране.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun cookieValue(cookies: String?, name: String): String? = cookies
        ?.split(";")
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter("=")
        ?.takeIf { it.isNotBlank() }

    private fun finishWith(token: String) {
        if (done) return
        done = true
        setResult(RESULT_OK, intent.putExtra(EXTRA_TOKEN, token))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        super.onDestroy()
    }
}
