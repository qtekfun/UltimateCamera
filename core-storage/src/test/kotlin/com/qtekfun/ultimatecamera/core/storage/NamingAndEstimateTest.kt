package com.qtekfun.ultimatecamera.core.storage

import java.time.LocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingAndEstimateTest {
    private val time = LocalDateTime.of(2026, 10, 16, 14, 30, 5)
    private val gib = 1024L * 1024 * 1024

    @Test
    fun clipNamesAreDeterministicAndShareTheBase() {
        val names = OutputNaming.names(time, "0", 1)
        assertEquals("UC_20261016_143005_CAM0_T001", names.base)
        assertEquals("UC_20261016_143005_CAM0_T001.mp4", names.video)
        assertEquals("UC_20261016_143005_CAM0_T001.wav", names.wav)
        assertEquals("UC_20261016_143005_CAM0_T001_proxy.mp4", names.proxy)
        assertEquals(names, OutputNaming.names(time, "0", 1))
        assertEquals("UC_20261016_143005_CAM2_T1234", OutputNaming.names(time, "2", 1234).base)
    }

    @Test
    fun namesUseOnlySafeCharacters() {
        val safe = Regex("[A-Za-z0-9_.-]+")
        for (id in listOf("0", "front/1:2", "", "ñ*?<>|\"\\", "a".repeat(40))) {
            val names = OutputNaming.names(time, id, 7)
            names.all.forEach { assertTrue(it, safe.matches(it)) }
        }
        assertEquals("front-1-2", OutputNaming.sanitizeCameraId("front/1:2"))
        assertEquals("X", OutputNaming.sanitizeCameraId(""))
        assertEquals(16, OutputNaming.sanitizeCameraId("a".repeat(40)).length)
    }

    @Test
    fun nextSkipsTakesThatCollideWithAnyFileIgnoringCase() {
        val existing = setOf(
            "UC_20261016_143005_CAM0_T001.mp4",
            "uc_20261016_143005_cam0_t002.WAV", // a sidecar alone also blocks the take
            "UC_20261016_143005_CAM1_T003.mp4" // another camera does not
        )
        assertEquals(3, OutputNaming.next(time, "0", 1, existing).take)
        assertEquals(1, OutputNaming.next(time, "1", 1, existing).take)
        assertEquals(5, OutputNaming.next(time, "0", 5, existing).take)
    }

    @Test
    fun remainingTimeKeepsAReserve() {
        val streams = RecordingStreams.UHD_60_10BIT // ~127.6 Mbit/s ≈ 57 GB per hour
        val free = 64L * gib
        val expectedSeconds = ((free - RecordingTimeEstimate.RESERVE_BYTES) * 8.0 / (streams.totalBitsPerSecond * 1.02)).toLong()
        assertEquals(expectedSeconds.seconds, RecordingTimeEstimate.remaining(free, streams))
        assertEquals(70L, RecordingTimeEstimate.remainingMinutes(free, streams))
        assertEquals(0L, RecordingTimeEstimate.remaining(RecordingTimeEstimate.RESERVE_BYTES / 2, streams).inWholeSeconds)
    }

    @Test
    fun freeSpaceStatus() {
        assertEquals(FreeSpaceStatus.FULL, RecordingTimeEstimate.classify(59.seconds))
        assertEquals(FreeSpaceStatus.LOW, RecordingTimeEstimate.classify(1.minutes))
        assertEquals(FreeSpaceStatus.LOW, RecordingTimeEstimate.classify(9.minutes))
        assertEquals(FreeSpaceStatus.OK, RecordingTimeEstimate.classify(10.minutes))
        assertEquals(FreeSpaceStatus.FULL, RecordingTimeEstimate.classify(0L, RecordingStreams(videoBitsPerSecond = 50_000_000)))
        assertEquals(FreeSpaceStatus.OK, RecordingTimeEstimate.classify(64L * gib, RecordingStreams(videoBitsPerSecond = 50_000_000)))
    }
}
