package com.qtekfun.ultimatecamera.core.gpu.meters

import org.junit.Test

/**
 * Pins every meter's CPU reference to a PNG (see [ReferencePng]). The on-device tests compare the GPU passes with the
 * same functions, so a change on either side shows up in one of the two suites.
 */
class MeterReferenceImageTest {
    private val w = MeterTestCharts.OVERLAY_WIDTH
    private val h = MeterTestCharts.OVERLAY_HEIGHT
    private val chart = MeterTestCharts.overlayChart()

    @Test
    fun overlayChart() = ReferencePng.check("overlay_chart.png", chart, w, h)

    @Test
    fun peaking() = overlay("overlay_peaking.png", MeterReferenceSettings.peaking)

    @Test
    fun zebrasAnimated() = overlay("overlay_zebras.png", MeterReferenceSettings.zebras, frameIndex = 3)

    @Test
    fun falseColor() = overlay("overlay_false_color.png", MeterReferenceSettings.falseColor)

    @Test
    fun guides() = overlay("overlay_guides.png", MeterReferenceSettings.guides)

    @Test
    fun desqueeze2x() = overlay("overlay_desqueeze_2x.png", MonitorOverlaySettings(desqueeze = Desqueeze.X2))

    @Test
    fun everythingAtOnce() = overlay("overlay_all.png", MeterReferenceSettings.all, frameIndex = 5)

    @Test
    fun scopeChart() = ReferencePng.check("scope_chart.png", MeterTestCharts.scopeChart(), MeterTestCharts.SCOPE_WIDTH, MeterTestCharts.SCOPE_HEIGHT)

    @Test
    fun histogramRgb() {
        val s = ScopeSettings(histogram = true, histogramMode = HistogramMode.RGB)
        ReferencePng.check("scope_histogram_rgb.png", ScopeDisplayMath.histogramDisplay(histogramTotals(), n, s, 256, 64), 256, 64)
    }

    @Test
    fun histogramLuma() {
        val s = ScopeSettings(histogram = true, histogramMode = HistogramMode.LUMA)
        ReferencePng.check("scope_histogram_luma.png", ScopeDisplayMath.histogramDisplay(histogramTotals(), n, s, 256, 64), 256, 64)
    }

    @Test
    fun waveformLuma() = waveform(WaveformMode.LUMA)

    @Test
    fun waveformRgbOverlay() = waveform(WaveformMode.RGB_OVERLAY)

    @Test
    fun waveformRgbParade() = waveform(WaveformMode.RGB_PARADE)

    private fun waveform(mode: WaveformMode) {
        val s = ScopeSettings(waveform = true, waveformMode = mode)
        val counts = ScopeMath.waveform(scope, MeterTestCharts.SCOPE_WIDTH, AccumulationCapacity.HALF_FLOAT)
        val image = ScopeDisplayMath.waveformDisplay(counts, MeterTestCharts.SCOPE_WIDTH, MeterTestCharts.SCOPE_HEIGHT, s, 192, 128)
        ReferencePng.check("scope_waveform_${mode.name.lowercase()}.png", image, 192, 128)
    }

    @Test
    fun vectorscope() {
        val s = ScopeSettings(vectorscope = true, vectorscopeSize = 128)
        val counts = ScopeMath.vectorscope(scope, 128, AccumulationCapacity.HALF_FLOAT)
        ReferencePng.check("scope_vectorscope.png", ScopeDisplayMath.vectorscopeDisplay(counts, 128, n, s, 128, 128), 128, 128)
    }

    private val scope = MeterTestCharts.scopeChart()
    private val n = scope.size

    private fun histogramTotals(): IntArray {
        val layers = ScopeBins.histogramLayers(n, AccumulationCapacity.HALF_FLOAT)
        return ScopeMath.sumLayers(ScopeMath.histogram(scope, layers), layers)
    }

    private fun overlay(name: String, settings: MonitorOverlaySettings, frameIndex: Long = 0) =
        ReferencePng.check(name, MonitorOverlayMath.render(chart, w, h, w, h, settings, frameIndex), w, h)
}

/** Capacities of the GPU count formats (see `AccumulationFormat`), for JVM tests that cannot load GL classes. */
internal object AccumulationCapacity {
    const val HALF_FLOAT = 2048
    const val UNORM8 = 255
}
