package com.ascendant.bellcontrol

data class Period(val name: String, val startMin: Int, val endMin: Int)

object Schedule {

    private fun t(h: Int, m: Int) = h * 60 + m

    // Bundled fallback schedules, matching the original HTML exactly (including the fixed
    // Prayer slot). Used only until the central sheet has been synced at least once, or
    // whenever a sync fails and there's no cached copy yet.
    val DEFAULT_ELEMENTARY = listOf(
        Period("Morning Assembly", t(6, 45), t(7, 0)),
        Period("1st Period", t(7, 0), t(7, 45)),
        Period("2nd Period", t(7, 45), t(8, 30)),
        Period("Snack Break", t(8, 30), t(8, 45)),
        Period("3rd Period", t(8, 45), t(9, 30)),
        Period("4th Period", t(9, 30), t(10, 15)),
        Period("Break", t(10, 15), t(10, 40)),
        Period("5th Period", t(10, 40), t(11, 20)),
        Period("6th Period", t(11, 20), t(12, 0)),
        Period("Prayer", t(12, 0), t(12, 15)),
        Period("7th Period", t(12, 15), t(12, 55)),
        Period("8th Period", t(12, 55), t(13, 35))
    )

    val DEFAULT_MIDDLE_HIGH = listOf(
        Period("Morning Assembly", t(6, 45), t(7, 0)),
        Period("1st Period", t(7, 0), t(7, 45)),
        Period("2nd Period", t(7, 45), t(8, 30)),
        Period("Snack Break", t(8, 30), t(8, 45)),
        Period("3rd Period", t(8, 45), t(9, 30)),
        Period("4th Period", t(9, 30), t(10, 15)),
        Period("5th Period", t(10, 15), t(11, 0)),
        Period("Break", t(11, 0), t(11, 25)),
        Period("6th Period", t(11, 25), t(12, 10)),
        Period("7th Period", t(12, 10), t(12, 55)),
        Period("Prayer", t(12, 55), t(13, 10)),
        Period("8th Period", t(13, 10), t(13, 50))
    )

    fun defaultFor(level: Level): List<Period> = when (level) {
        Level.ELEMENTARY -> DEFAULT_ELEMENTARY
        Level.MIDDLE_HIGH -> DEFAULT_MIDDLE_HIGH
        Level.ALL_LEVELS -> emptyList() // combined view renders ELEMENTARY + MIDDLE_HIGH separately
    }

    fun boundaries(sched: List<Period>): List<Int> {
        if (sched.isEmpty()) return emptyList()
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
            "Prayer" -> "Prayer time"
            else -> name
        }
    }
}
