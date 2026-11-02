package me.timschneeberger.rootlessjamesdsp.peq.importer

import me.timschneeberger.rootlessjamesdsp.peq.PeqModel
import org.junit.Assert.*
import org.junit.Test

class GraphicEqScreenshotCurveTest {
    @Test fun userScreenshotCurveFitsDefaultImportWithoutChangingItsAbsoluteLevel() {
        // Transcribed from the user's supplied GraphicEQ screenshot, not a verified measurement.
        val curve = GraphicEqParser.parse(TEXT)
        assertEquals(127, curve.points.size)
        val fit = GraphicEqFitter.fit(curve, PeqModel.defaultState(), GraphicEqFitOptions())
        println("Screenshot curve: ${fit.state.bands.size} bands, max=${fit.maxErrorDb} dB, " +
            "RMS=${fit.rmsErrorDb} dB, ${fit.elapsedMillis} ms")
        assertTrue("Default import must fit this representative curve within 0.5 dB: ${fit.maxErrorDb}", fit.targetMet)
        assertTrue(fit.state.bands.size <= 30)
        assertTrue(fit.fittedDb.all { it.isFinite() })
        val bass = fit.frequencies.indices.minBy { kotlin.math.abs(fit.frequencies[it] - 87.0) }
        assertEquals(-15.9, fit.fittedDb[bass], 0.5)
        assertEquals(2, fit.offsetBandCount)
    }

    companion object {
        const val TEXT = """GraphicEQ: 20 -6.7; 21 -6.8; 22 -6.9; 23 -7.0; 24 -7.1; 26 -7.3;
27 -7.4; 29 -7.6; 30 -7.8; 32 -8.0; 34 -8.3; 36 -8.6; 38 -8.9; 40 -9.2;
43 -9.5; 45 -9.9; 48 -10.3; 50 -10.6; 53 -11.0; 56 -11.5; 59 -11.9;
63 -12.5; 66 -13.0; 70 -13.6; 74 -14.2; 78 -15.0; 83 -15.6; 87 -15.9;
92 -15.8; 97 -15.0; 103 -13.8; 109 -12.7; 115 -11.7; 121 -10.9; 128 -10.1;
136 -9.5; 143 -9.0; 151 -8.5; 160 -8.2; 169 -7.9; 178 -7.7; 188 -7.7;
199 -7.7; 210 -7.8; 222 -8.0; 235 -8.2; 248 -8.3; 262 -8.2; 277 -8.0;
292 -7.8; 309 -7.5; 326 -7.2; 345 -7.0; 364 -6.9; 385 -6.9; 406 -6.9;
429 -7.1; 453 -7.4; 479 -7.8; 506 -8.1; 534 -8.5; 565 -8.8; 596 -9.1;
630 -9.3; 665 -9.5; 703 -9.8; 743 -10.0; 784 -10.3; 829 -10.6; 875 -10.8;
924 -10.7; 977 -10.5; 1032 -10.1; 1090 -9.8; 1151 -9.5; 1216 -9.3;
1284 -9.1; 1357 -9.0; 1433 -8.9; 1514 -8.9; 1599 -8.9; 1689 -8.9;
1784 -8.9; 1885 -8.9; 1991 -8.9; 2103 -8.8; 2221 -8.6; 2347 -8.2;
2479 -7.6; 2618 -6.6; 2766 -5.3; 2921 -4.1; 3086 -3.3; 3260 -3.0;
3443 -2.7; 3637 -2.4; 3842 -2.3; 4058 -2.6; 4287 -3.1; 4528 -3.7;
4783 -4.3; 5052 -4.3; 5337 -3.8; 5637 -3.3; 5955 -3.1; 6290 -3.3;
6644 -3.7; 7018 -4.4; 7414 -4.8; 7831 -4.1; 8272 -3.1; 8738 -2.0;
9230 -0.9; 9749 0.0; 10298 -0.3; 10878 -1.8; 11490 -3.9; 12137 -5.9;
12821 -5.2; 13543 -3.0; 14305 -1.1; 15110 -1.6; 15961 -3.1; 16860 -4.0;
17809 -4.3; 18812 -4.5; 19871 -4.6"""
    }
}
