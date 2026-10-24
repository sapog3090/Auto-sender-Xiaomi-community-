package com.xmsender.blunlock

import android.os.SystemClock
import java.time.Instant
import java.time.ZoneId

/** «Точные» часы: системное время + поправка NTP, отсчёт по монотонному таймеру. */
object TrueClock {
    val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")
    val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

    @Volatile private var baseElapsed = SystemClock.elapsedRealtime()
    @Volatile private var baseUtc = System.currentTimeMillis()
    @Volatile var offsetMs = 0L
        private set
    @Volatile var synced = false
        private set

    fun now(): Long = baseUtc + (SystemClock.elapsedRealtime() - baseElapsed)

    fun applyOffset(offset: Long) {
        baseElapsed = SystemClock.elapsedRealtime()
        baseUtc = System.currentTimeMillis() + offset
        offsetMs = offset
        synced = true
    }

    /** Ближайшая полночь по Пекину (момент сброса квоты), в мс UTC. */
    fun nextResetUtc(fromUtc: Long = now()): Long {
        val nowBj = Instant.ofEpochMilli(fromUtc).atZone(BEIJING)
        return nowBj.toLocalDate().plusDays(1).atStartOfDay(BEIJING).toInstant().toEpochMilli()
    }
}
