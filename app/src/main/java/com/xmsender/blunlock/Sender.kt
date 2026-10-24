package com.xmsender.blunlock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/** Живёт дольше Activity (переживает поворот экрана). */
object Sender {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private var job: Job? = null

    fun log(msg: String) {
        val t = Instant.ofEpochMilli(TrueClock.now()).atZone(TrueClock.MOSCOW).format(timeFmt)
        val updated = "[$t] $msg\n" + _log.value
        _log.value = updated.take(20_000)
    }

    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
        log("Остановлено.")
    }

    fun syncTime() = scope.launch {
        log("Синхронизация времени...")
        val s = withContext(Dispatchers.IO) { Sntp.bestSample(::log) }
        if (s == null) {
            log("⚠ NTP недоступен, используются часы телефона.")
        } else {
            TrueClock.applyOffset(s.offsetMs)
            log("Время синхронизировано (${s.host}): поправка ${s.offsetMs} мс, RTT ${s.rttMs} мс")
        }
    }

    fun checkState(api: MiApi) = scope.launch {
        try {
            val json = withContext(Dispatchers.IO) { api.state() }
            log("Статус: " + MiParse.describeState(json))
        } catch (e: Exception) {
            log("Ошибка статуса: ${e.message}")
        }
    }

    fun applyNow(api: MiApi) = scope.launch {
        try {
            val json = withContext(Dispatchers.IO) { api.apply() }
            log("Заявка: " + MiParse.parseApply(json).second + "\n  raw: $json")
        } catch (e: Exception) {
            log("Ошибка заявки: ${e.message}")
        }
    }

    fun startScheduled(api: MiApi, startOffsetMs: Long, count: Int, intervalMs: Long) {
        if (_running.value) return
        _running.value = true
        job = scope.launch {
            try {
                runScheduled(api, startOffsetMs, count, intervalMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Ошибка: ${e.message}")
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun waitUntil(utc: Long) {
        while (true) {
            val remaining = utc - TrueClock.now()
            if (remaining <= 0) return
            if (remaining > 25) delay(remaining - 20) else yield()
        }
    }

    private suspend fun runScheduled(api: MiApi, startOffsetMs: Long, count: Int, intervalMs: Long) {
        if (!TrueClock.synced) {
            val s = withContext(Dispatchers.IO) { Sntp.bestSample(::log) }
            if (s != null) {
                TrueClock.applyOffset(s.offsetMs)
                log("Время синхронизировано: поправка ${s.offsetMs} мс")
            } else {
                log("⚠ NTP недоступен, используются часы телефона.")
            }
        }

        val state = withContext(Dispatchers.IO) { api.state() }
        log("Статус: " + MiParse.describeState(state))
        val data = state.optJSONObject("data")
        if (state.optInt("code", -1) != 0 || data == null) {
            log("Не удалось получить статус — проверьте токен."); return
        }
        if (data.optInt("is_pass") == 1 || data.optInt("button_state") in setOf(2, 3)) return

        val target = TrueClock.nextResetUtc()
        val targetMsk = Instant.ofEpochMilli(target).atZone(TrueClock.MOSCOW).format(timeFmt)
        log("Цель: $targetMsk МСК. Держите экран включённым и приложение открытым!")

        // За минуту до сброса — замер задержки до сервера (заодно греем соединение).
        waitUntil(target - 60_000)
        val rtts = mutableListOf<Long>()
        repeat(3) {
            val t0 = TrueClock.now()
            try {
                withContext(Dispatchers.IO) { api.state() }
                rtts += TrueClock.now() - t0
            } catch (e: Exception) {
                log("Пинг: ${e.message}")
            }
        }
        val oneWay = if (rtts.isEmpty()) 100L else rtts.sorted()[rtts.size / 2] / 2
        log("RTT: $rtts мс → упреждение $oneWay мс")

        // За 5 секунд — ещё раз прогреваем соединение, чтобы не тратить время на TLS.
        waitUntil(target - 5_000)
        try { withContext(Dispatchers.IO) { api.state() } } catch (_: Exception) {}

        val stopFlag = AtomicBoolean(false)
        val firstSend = target + startOffsetMs - oneWay
        coroutineScope {
            for (i in 0 until count) {
                if (stopFlag.get() || !isActive) break
                waitUntil(firstSend + i * intervalMs)
                val n = i + 1
                launch(Dispatchers.IO) {
                    val sentRel = TrueClock.now() - target
                    try {
                        val json = api.apply()
                        val gotRel = TrueClock.now() - target
                        val (outcome, msg) = MiParse.parseApply(json)
                        val raw = if (outcome == ApplyOutcome.ERROR || outcome == ApplyOutcome.MAYBE) "\n  raw: $json" else ""
                        log("#$n отпр. ${sentRel}мс, ответ ${gotRel}мс: $msg$raw")
                        if (outcome == ApplyOutcome.APPROVED || outcome == ApplyOutcome.BLOCKED) {
                            stopFlag.set(true)
                        }
                    } catch (e: Exception) {
                        log("#$n отпр. ${sentRel}мс: ошибка ${e.message}")
                    }
                }
            }
        }

        delay(1000)
        try {
            val after = withContext(Dispatchers.IO) { api.state() }
            log("Итоговый статус: " + MiParse.describeState(after))
        } catch (e: Exception) {
            log("Итоговый статус: ошибка ${e.message}")
        }
    }
}
