package hu.szfvbus.data

/**
 * Az adatforrás minden székesfehérvári vonalnak ugyanazt a sötét színt adja, amitől az élő térkép nehezen
 * olvasható. Ehelyett minden vonal egy palettából kap állandó színt; a változatok (11, 11A) az alapvonal
 * színét kapják.
 */
object LineColors {
    private val palette = intArrayOf(
        0xFFE53935.toInt(), // piros
        0xFF1E88E5.toInt(), // kék
        0xFF43A047.toInt(), // zöld
        0xFFFB8C00.toInt(), // narancs
        0xFF8E24AA.toInt(), // lila
        0xFF00897B.toInt(), // kékeszöld
        0xFFD81B60.toInt(), // rózsaszín
        0xFF3949AB.toInt(), // indigó
        0xFF6D4C41.toInt(), // barna
        0xFF00ACC1.toInt(), // ciánkék
        0xFF558B2F.toInt(), // olajzöld
        0xFFF4511E.toInt(), // sötétnarancs
        0xFF5E35B1.toInt(), // sötétlila
        0xFF9E9D24.toInt(), // citromzöld
    )

    const val INTERCITY: Int = 0xFF455A64.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()

    private val digits = "^(\\d+)".toRegex()

    fun forLine(shortName: String, isLocal: Boolean = true): Int {
        if (!isLocal) return INTERCITY
        val base = digits.find(shortName)?.groupValues?.get(1)?.toIntOrNull()
            ?: shortName.hashCode().let { if (it < 0) -it else it }
        return palette[base % palette.size]
    }

    fun hex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)
}
