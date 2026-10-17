package com.qtekfun.ultimatecamera.feature.multicam.pairing

/** "Last seen" of a paired phone, coarse enough for a list. Pure. */
sealed interface LastSeen {
    data object Never : LastSeen

    data object JustNow : LastSeen

    data class Minutes(val value: Long) : LastSeen

    data class Hours(val value: Long) : LastSeen

    data class Days(val value: Long) : LastSeen

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val DAY_MS = 24 * HOUR_MS

        fun of(lastSeenEpochMs: Long?, nowEpochMs: Long): LastSeen {
            val seen = lastSeenEpochMs ?: return Never
            val age = (nowEpochMs - seen).coerceAtLeast(0)
            return when {
                age < MINUTE_MS -> JustNow
                age < HOUR_MS -> Minutes(age / MINUTE_MS)
                age < DAY_MS -> Hours(age / HOUR_MS)
                else -> Days(age / DAY_MS)
            }
        }
    }
}
