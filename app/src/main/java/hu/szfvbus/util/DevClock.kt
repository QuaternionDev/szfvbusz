package hu.szfvbus.util

/**
 * Az alkalmazás órája. Alapesetben a rendszeridő; a fejlesztői mód más időpontra teheti és gyorsabban
 * járathatja, hogy az indulások, az utazás és a szimulált buszok bármikor kipróbálhatók legyenek.
 */
object DevClock {
    @Volatile private var anchorRealMs = 0L
    @Volatile private var anchorSimMs = 0L
    @Volatile var speed = 1.0
        private set
    @Volatile var isShifted = false
        private set

    fun nowMs(): Long {
        val real = System.currentTimeMillis()
        if (!isShifted) return real
        return anchorSimMs + ((real - anchorRealMs) * speed).toLong()
    }

    /** A [simMs] időponttól folytatja, a valós tempó [speed]-szeresével. */
    fun set(simMs: Long, speed: Double = this.speed) {
        anchorRealMs = System.currentTimeMillis()
        anchorSimMs = simMs
        this.speed = speed
        isShifted = speed != 1.0 || kotlin.math.abs(simMs - anchorRealMs) > 1000
    }

    fun setSpeed(speed: Double) = set(nowMs(), speed)

    fun reset() {
        speed = 1.0
        isShifted = false
    }
}
