package com.xmsender.blunlock

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Клиент API глобального Xiaomi Community (тот же, что использует приложение).
 * Авторизация — cookie new_bbs_serviceToken.
 */
class MiApi(private val token: String, private val deviceId: String) {

    companion object {
        private const val BASE = "https://sgp-api.buy.mi.com/bbs/api/global/"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 32
            })
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .build()
    }

    private fun request(path: String): Request.Builder = Request.Builder()
        .url(BASE + path)
        .header(
            "Cookie",
            "new_bbs_serviceToken=$token;versionCode=500411;versionName=5.4.11;deviceId=$deviceId;"
        )
        .header("User-Agent", "okhttp/4.12.0")
        .header("Accept", "application/json")

    private fun execute(req: Request): JSONObject {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(200)}")
            return JSONObject(body)
        }
    }

    /** Статус разрешения на разблокировку (блокирующий вызов). */
    fun state(): JSONObject = execute(request("user/bl-switch/state").get().build())

    /** Подача заявки (блокирующий вызов). */
    fun apply(): JSONObject = execute(
        request("apply/bl-auth")
            .post("""{"is_retry":true}""".toRequestBody(JSON_TYPE))
            .build()
    )
}

/** Итог разбора ответа на заявку. */
enum class ApplyOutcome { APPROVED, LIMIT, BLOCKED, MAYBE, ERROR }

object MiParse {

    fun describeState(json: JSONObject): String {
        if (json.optInt("code", -1) != 0) {
            return "Ошибка (code=${json.opt("code")}): ${json.optString("msg")}"
        }
        val data = json.optJSONObject("data") ?: return "Пустой ответ: $json"
        val deadline = data.optString("deadline_format")
        if (data.optInt("is_pass") == 1) {
            return "✅ Разблокировка УЖЕ одобрена (до $deadline). Идите в Mi Unlock Tool."
        }
        return when (data.optInt("button_state")) {
            1 -> "Можно подавать заявку."
            2 -> "⛔ Аккаунт заблокирован для заявок до $deadline (месяц/день)."
            3 -> "⛔ Аккаунту меньше 30 дней — подать заявку пока нельзя."
            else -> "Неизвестный статус: $data"
        }
    }

    fun parseApply(json: JSONObject): Pair<ApplyOutcome, String> {
        val code = json.optInt("code", -1)
        val data = json.optJSONObject("data")
        if (code == 0 && data != null) {
            val deadline = data.optString("deadline_format")
            return when (data.optInt("apply_result")) {
                1 -> ApplyOutcome.APPROVED to "✅ ОДОБРЕНО! Проверьте статус и идите в Mi Unlock Tool."
                3 -> ApplyOutcome.LIMIT to "Квота исчерпана, следующая попытка: $deadline"
                4 -> ApplyOutcome.BLOCKED to "⛔ Аккаунт заблокирован до $deadline"
                else -> ApplyOutcome.ERROR to "Неизвестный результат: $data"
            }
        }
        return when (code) {
            100001 -> ApplyOutcome.ERROR to "Заявка отклонена (100001): ${json.optString("msg")}"
            100003 -> ApplyOutcome.MAYBE to "Возможно одобрено (100003) — проверьте статус"
            else -> ApplyOutcome.ERROR to "code=$code ${json.optString("msg")}"
        }
    }
}
