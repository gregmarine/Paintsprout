package com.symmetricalpalmtree.paintsprout.paint

import android.content.Context
import android.os.Build
import kotlin.math.max
import kotlin.math.min

/**
 * Screen-size calibration: turns the panel's true pixels-per-inch into a stored,
 * per-device value so on-screen art can be made 1:1 with a printed medium.
 *
 * Resolution alone says nothing about physical size — that only comes from PPI.
 * Android's [android.util.DisplayMetrics.xdpi]/[android.util.DisplayMetrics.ydpi]
 * are *meant* to be the true physical density, but OEMs frequently report garbage
 * (rounding them, or copying [android.util.DisplayMetrics.densityDpi] in). So we
 * use them only as an initial guess ([reportedPpi]); the trustworthy value comes
 * from the user matching an on-screen outline to a physical reference object of
 * known size ([CalibrationActivity]), which we persist here.
 *
 * Values are keyed per device (manufacturer + model + resolution) so several
 * tablets each keep their own calibration in the same install.
 */
object Calibration {

    const val MM_PER_IN = 25.4f

    private const val PREFS = "paintsprout.calibration"
    private const val KEY_PREFIX = "ppi:"

    /** A physical object of known size the user lines the on-screen outline up to. */
    data class Reference(
        val id: String,
        val label: String,
        /** Longer edge in mm; for [isRuler] this is the initial adjustable length. */
        val longMm: Float,
        /** Shorter edge in mm; 0 marks a free-length ruler with no fixed aspect. */
        val shortMm: Float,
    ) {
        val isRuler: Boolean get() = shortMm <= 0f
        /** Short/long ratio, so a fixed-aspect outline can follow the long edge. */
        val aspect: Float get() = if (longMm > 0f) shortMm / longMm else 0f
    }

    /** ID-1 is the default: a credit/ID card is exactly 85.60 × 53.98 mm worldwide. */
    val REFERENCES: List<Reference> = listOf(
        Reference("id1", "Credit / ID card", 85.60f, 53.98f),
        Reference("business", "Business card (US)", 88.90f, 50.80f),
        Reference("index", "Index card (3×5\")", 127.0f, 76.20f),
        Reference("ruler", "Ruler", 100f, 0f),
    )

    val DEFAULT_REFERENCE: Reference get() = REFERENCES.first()

    // --- Stored + reported PPI ---------------------------------------------

    /**
     * The OEM-reported physical density, averaged over both axes. Used only as the
     * starting guess and the fallback when no calibration is saved. Falls back to
     * the bucketed [android.util.DisplayMetrics.densityDpi] when xdpi/ydpi are
     * outside any plausible range (a sign the OEM filled them with junk).
     */
    fun reportedPpi(context: Context): Float {
        val dm = context.resources.displayMetrics
        val avg = (dm.xdpi + dm.ydpi) / 2f
        return if (avg in 40f..2000f) avg else dm.densityDpi.toFloat()
    }

    /** The user-calibrated PPI for this device, or null if never calibrated. */
    fun savedPpi(context: Context): Float? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = deviceKey(context)
        return if (prefs.contains(key)) prefs.getFloat(key, 0f).takeIf { it > 0f } else null
    }

    /** The PPI to actually use: the calibrated value if present, else [reportedPpi]. */
    fun effectivePpi(context: Context): Float = savedPpi(context) ?: reportedPpi(context)

    /** True once the user has calibrated this device at least once. */
    fun isCalibrated(context: Context): Boolean = savedPpi(context) != null

    fun save(context: Context, ppi: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(deviceKey(context), ppi)
            .apply()
    }

    /** Drops this device's calibration, reverting to [reportedPpi]. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(deviceKey(context))
            .apply()
    }

    // --- Unit conversion ----------------------------------------------------

    fun mmToPx(mm: Float, ppi: Float): Float = mm / MM_PER_IN * ppi
    fun pxToMm(px: Float, ppi: Float): Float = px / ppi * MM_PER_IN
    fun inToPx(inches: Float, ppi: Float): Float = inches * ppi
    fun pxToIn(px: Float, ppi: Float): Float = px / ppi

    // --- Device identity ----------------------------------------------------

    /**
     * A key stable per physical device model + panel, so distinct tablets keep
     * separate calibrations. Resolution is stored orientation-independently.
     */
    private fun deviceKey(context: Context): String {
        val dm = context.resources.displayMetrics
        val hi = max(dm.widthPixels, dm.heightPixels)
        val lo = min(dm.widthPixels, dm.heightPixels)
        return "$KEY_PREFIX${Build.MANUFACTURER}:${Build.MODEL}:${hi}x$lo"
    }
}
