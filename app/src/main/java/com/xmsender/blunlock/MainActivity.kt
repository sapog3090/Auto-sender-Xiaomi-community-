package com.xmsender.blunlock

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvAccount: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvLog: TextView
    private lateinit var etOffset: EditText
    private lateinit var etCount: EditText
    private lateinit var etInterval: EditText
    private lateinit var btnStart: Button

    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss.S")

    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val token = r.data?.getStringExtra(LoginActivity.EXTRA_TOKEN)
        if (r.resultCode == RESULT_OK && !token.isNullOrBlank()) {
            prefs.token = token
            updateAccount()
            Sender.log("Токен получен после входа.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvAccount = findViewById(R.id.tvAccount)
        tvClock = findViewById(R.id.tvClock)
        tvLog = findViewById(R.id.tvLog)
        etOffset = findViewById(R.id.etOffset)
        etCount = findViewById(R.id.etCount)
        etInterval = findViewById(R.id.etInterval)
        btnStart = findViewById(R.id.btnStart)

        etOffset.setText(prefs.offsetMs.toString())
        etCount.setText(prefs.count.toString())
        etInterval.setText(prefs.intervalMs.toString())
        updateAccount()

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            login.launch(android.content.Intent(this, LoginActivity::class.java))
        }
        findViewById<Button>(R.id.btnToken).setOnClickListener { askToken() }
        findViewById<Button>(R.id.btnSync).setOnClickListener { Sender.syncTime() }
        findViewById<Button>(R.id.btnState).setOnClickListener { api()?.let(Sender::checkState) }
        findViewById<Button>(R.id.btnNow).setOnClickListener { api()?.let(Sender::applyNow) }
        btnStart.setOnClickListener {
            if (Sender.running.value) {
                Sender.stop()
                return@setOnClickListener
            }
            val api = api() ?: return@setOnClickListener
            val offset = etOffset.text.toString().toLongOrNull()
            val count = etCount.text.toString().toIntOrNull()
            val interval = etInterval.text.toString().toLongOrNull()
            if (offset == null || count == null || count !in 1..50 || interval == null || interval < 10) {
                toast("Проверьте параметры (попыток 1–50, интервал ≥ 10 мс)")
                return@setOnClickListener
            }
            prefs.offsetMs = offset
            prefs.count = count
            prefs.intervalMs = interval
            Sender.startScheduled(api, offset, count, interval)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Sender.log.collect { tvLog.text = it } }
                launch {
                    Sender.running.collect { running ->
                        btnStart.text = if (running) "Стоп" else "Запустить (ждать сброса в 19:00 МСК)"
                        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                launch {
                    while (true) {
                        updateClock()
                        delay(100)
                    }
                }
            }
        }

        if (!TrueClock.synced) Sender.syncTime()
    }

    private fun updateClock() {
        val now = TrueClock.now()
        val msk = Instant.ofEpochMilli(now).atZone(TrueClock.MOSCOW).format(clockFmt)
        val left = (TrueClock.nextResetUtc(now) - now) / 1000
        val sync = if (TrueClock.synced) "поправка ${TrueClock.offsetMs} мс" else "не синхронизировано"
        tvClock.text = "МСК: $msk ($sync)\nДо сброса квоты: %02d:%02d:%02d".format(
            left / 3600, left / 60 % 60, left % 60
        )
    }

    private fun updateAccount() {
        val t = prefs.token
        tvAccount.text = if (t.isBlank()) "Аккаунт: не выполнен вход"
        else "Аккаунт: токен сохранён (…${t.takeLast(6)})"
    }

    private fun api(): MiApi? {
        val t = prefs.token
        if (t.isBlank()) {
            toast("Сначала войдите в Mi аккаунт")
            return null
        }
        return MiApi(t, prefs.deviceId)
    }

    private fun askToken() {
        val input = EditText(this).apply {
            hint = "new_bbs_serviceToken"
            setText(prefs.token)
        }
        AlertDialog.Builder(this)
            .setTitle("Значение cookie new_bbs_serviceToken")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.token = input.text.toString().removePrefix("new_bbs_serviceToken=")
                updateAccount()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
