package com.xmsender.blunlock

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Простейший SNTP-клиент: оценивает поправку системных часов. */
object Sntp {

    private const val NTP_EPOCH_OFFSET = 2_208_988_800L
    private val HOSTS = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")

    data class Sample(val offsetMs: Long, val rttMs: Long, val host: String)

    private fun readTimestamp(buf: ByteArray, at: Int): Long {
        var seconds = 0L
        var fraction = 0L
        for (i in 0 until 4) seconds = (seconds shl 8) or (buf[at + i].toLong() and 0xFF)
        for (i in 4 until 8) fraction = (fraction shl 8) or (buf[at + i].toLong() and 0xFF)
        return (seconds - NTP_EPOCH_OFFSET) * 1000 + (fraction * 1000 shr 32)
    }

    private fun query(host: String): Sample {
        DatagramSocket().use { socket ->
            socket.soTimeout = 3000
            val address = InetAddress.getByName(host)
            val buf = ByteArray(48)
            buf[0] = 0x1B // LI=0, VN=3, Mode=3 (client)
            val t0Wall = System.currentTimeMillis()
            val t0 = SystemClock.elapsedRealtime()
            socket.send(DatagramPacket(buf, buf.size, address, 123))
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            val t3 = t0Wall + (SystemClock.elapsedRealtime() - t0)
            val t1 = readTimestamp(buf, 32)
            val t2 = readTimestamp(buf, 40)
            val offset = ((t1 - t0Wall) + (t2 - t3)) / 2
            val rtt = (t3 - t0Wall) - (t2 - t1)
            return Sample(offset, rtt, host)
        }
    }

    /** Несколько замеров, берём тот, у которого минимальная задержка. Блокирующий вызов. */
    fun bestSample(log: (String) -> Unit): Sample? {
        val samples = mutableListOf<Sample>()
        for (host in HOSTS) {
            for (i in 0 until 4) {
                try {
                    samples += query(host)
                } catch (e: Exception) {
                    log("NTP $host: ${e.message}")
                    break
                }
            }
            if (samples.size >= 4) break
        }
        return samples.minByOrNull { it.rttMs }
    }
}
