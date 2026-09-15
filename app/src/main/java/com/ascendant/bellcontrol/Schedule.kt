package com.ascendant.bellcontrol

data class Period(val name: String, val startMin: Int, val endMin: Int)

object Schedule {

    private fun t(h: Int, m: Int) = h * 60 + m
    const val PRAYER_NAME = "Prayer"
    private const val PRAYER_DURATION_MIN = 15

    // Identical for both levels, and never affected by Dhuhr — matches both HTML files exactly.
    private val COMMON_MORNING = listOf(
        Triple("Morning Assembly", t(6, 45), t(7, 0)),
        Triple("1st Period", t(7, 0), t(7, 45)),
        Triple("2nd Period", t(7, 45), t(8, 30)),
        Triple("Snack Break", t(8, 30), t(8, 45)),
        Triple("3rd Period", t(8, 45), t(9, 30)),
        Triple("4th Period", t(9, 30), t(10, 15))
    )

    // From bell-control-elementary.html, up to (and including) the period right before Prayer.
    private val ELEM_PRE_PRAYER_TAIL = listOf(
        Triple("Break", t(10, 15), t(10, 40)),
        Triple("5th Period", t(10, 40), t(11, 20)),
        Triple("6th Period", t(11, 20), t(12, 0))
    )
    private val ELEM_POST_PRAYER = listOf("7th Period" to 40, "8th Period" to 40)

    // From bell-control-middlehigh.html, up to (and including) the period right before Prayer.
    private val MSHS_PRE_PRAYER_TAIL = listOf(
        Triple("5th Period", t(10, 15), t(11, 0)),
        Triple("Break", t(11, 0), t(11, 25)),
        Triple("6th Period", t(11, 25), t(12, 10)),
        Triple("7th Period", t(12, 10), t(12, 55))
    )
    private val MSHS_POST_PRAYER = listOf("8th Period" to 40)

    /**
     * Builds today's full period list for a level.
     *
     * Prayer is anchored to [dhuhrMinutes]: it is never scheduled before Dhuhr has actually
     * occurred. On almost every day that lines up exactly with the school's normal fixed slot
     * (12:00 Elementary / 12:55 Middle & High, same as the HTML). On the rare days real solar
     * Dhuhr drifts later than that slot, Prayer — and everything after it — is pushed back by
     * the same amount, so no lesson is ever interrupted mid-period and Prayer is never held
     * before it's technically due. Elementary and Middle & High are resolved completely
     * independently, so they are never forced to pray at the same time.
     */
    fun buildDailySchedule(level: Level, dhuhrMinutes: Int): List<Period> {
        val pre = COMMON_MORNING + when (level) {
            Level.ELEMENTARY -> ELEM_PRE_PRAYER_TAIL
            Level.MIDDLE_HIGH -> MSHS_PRE_PRAYER_TAIL
        }

        val result = mutableListOf<Period>()
        pre.forEach { (name, s, e) -> result.add(Period(name, s, e)) }

        val originalPrePrayerEnd = pre.last().third
        val prayerStart = maxOf(dhuhrMinutes, originalPrePrayerEnd)
        val prayerEnd = prayerStart + PRAYER_DURATION_MIN
        result.add(Period(PRAYER_NAME, prayerStart, prayerEnd))

        var cursor = prayerEnd
        val post = when (level) {
            Level.ELEMENTARY -> ELEM_POST_PRAYER
            Level.MIDDLE_HIGH -> MSHS_POST_PRAYER
        }
        post.forEach { (name, duration) ->
            result.add(Period(name, cursor, cursor + duration))
            cursor += duration
        }
        return result
    }

    fun boundaries(sched: List<Period>): List<Int> {
        val list = sched.map { it.startMin }.toMutableList()
        list.add(sched.last().endMin)
        return list
    }

    /** Matches the announcement wording in your HTML files exactly. */
    fun announcementFor(name: String, isDismissal: Boolean): String {
        if (isDismissal) return "End of school day"
        val m = Regex("""^(\d+)(st|nd|rd|th) Period$""").find(name)
        if (m != null) return "Start of lesson ${m.groupValues[1]}"
        return when (name) {
            "Morning Assembly" -> "Morning assembly"
            "Snack Break" -> "Snack break"
            "Break" -> "Break time"
            PRAYER_NAME -> "Prayer time"
            else -> name
        }
    }
}
