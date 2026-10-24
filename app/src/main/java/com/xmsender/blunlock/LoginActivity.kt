package com.xmsender.blunlock

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Вход в Mi аккаунт через официальную страницу Xiaomi в WebView.
 * После входа забираем cookie new_bbs_serviceToken — пароль приложение не видит.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOKEN = "token"
        private const val LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin" +
            "?callback=https%3A%2F%2Fsgp-api.buy.mi.com%2Fbbs%2Fapi%2Fglobal%2Fuser%2Flogin-back" +
            "%3Ffollowup%3Dhttps%253A%252F%252Fnew.c.mi.com%252Fglobal%252F%26sid%3D18n_bbs_global" +
            "&sid=18n_bbs_global&_locale=en_US&checkSafeAddress=true"
        private val COOKIE_URLS = listOf(
            "https://sgp-api.buy.mi.com",
            "https://buy.mi.com",
            "https://new.c.mi.com",
            "https://c.mi.com",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    private val poller = object : Runnable {
        override fun run() {
            checkCookies()
            if (!done) handler.postDelayed(this, 1000)
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
            val token = cm.getCookie(u)
                ?.split(";")
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("new_bbs_serviceToken=") }
                ?.substringAfter("=")
            if (!token.isNullOrBlank()) {
                done = true
                setResult(RESULT_OK, intent.putExtra(EXTRA_TOKEN, token))
                finish()
                return
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        super.onDestroy()
    }
}
