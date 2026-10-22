package hu.szfvbus.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object TimeUtil {
    val ZONE: ZoneId = ZoneId.of("Europe/Budapest")

    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private val serviceDate = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val isoDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val isoTime = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun nowSec(): Long = DevClock.nowMs() / 1000

    fun nowDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(DevClock.nowMs()), ZONE)

    fun today(): LocalDate = nowDateTime().toLocalDate()

    fun clock(epochSec: Long): String =
        clock.format(Instant.ofEpochSecond(epochSec).atZone(ZONE))

    fun serviceDate(date: LocalDate): String = serviceDate.format(date)

    fun serviceDateOf(serviceDayEpochSec: Long): LocalDate =
        Instant.ofEpochSecond(serviceDayEpochSec).atZone(ZONE).toLocalDate()

    fun isoDate(dateTime: LocalDateTime): String = isoDate.format(dateTime)

    fun isoTime(dateTime: LocalDateTime): String = isoTime.format(dateTime)

    /** Egész percek az [epochSec] időpontig, lefelé kerekítve; negatív, ha már elmúlt. */
    fun minutesUntil(epochSec: Long, now: Long = nowSec()): Int =
        Math.floorDiv(epochSec - now, 60L).toInt()

    /** "most", "3 perc", "1 ó 5 p", vagy a távoli indulásoknál az óra:perc. */
    fun relative(epochSec: Long, now: Long = nowSec()): String {
        val min = minutesUntil(epochSec, now)
        return when {
            min <= 0 -> "most"
            min < 60 -> "$min perc"
            else -> clock(epochSec)
        }
    }

    /** Olvasható késés, pl. "3 perc késés", "1 perc siet", "pontos". */
    fun delayText(delaySec: Int): String {
        val min = (delaySec / 60.0).roundToInt()
        return when {
            min > 0 -> "$min perc késés"
            min < 0 -> "${-min} perc siet"
            else -> "pontos"
        }
    }

    fun durationText(seconds: Long): String {
        val totalMin = ((seconds + 59) / 60).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h ó $m p" else "$m perc"
    }
}
