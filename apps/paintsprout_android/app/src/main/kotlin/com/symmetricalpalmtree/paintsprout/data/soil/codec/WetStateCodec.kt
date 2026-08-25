package com.symmetricalpalmtree.paintsprout.data.soil.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a watercolour stroke needs in order to be replayed as the thing the user
 * actually saw.
 *
 * The wet simulation runs on the wall clock: a wash keeps moving while the pen
 * pauses and for a while after pen-up. That makes it the one part of this app
 * where "replay the ops" is not automatically honest — a rebuild days later would
 * re-run the simulation for a different number of ticks and produce a different
 * painting.
 *
 * Three things fix it, and all three live here:
 *
 * - **[schedule]** records how many points had been stamped before each tick, so
 *   replaying stamps and ticks in that order re-runs exactly the simulation the
 *   live preview showed.
 * - **[crop]** is the buffer region the live sim finished on. The live crop grows
 *   as the stroke wanders; the bake replays over the *final* crop from the start,
 *   so both see the same field extent.
 * - **[dryFreeze]** is the per-point drying progress the stroke was frozen at if
 *   something cut its drying short — a new stroke, an undo, a surface change. A
 *   wash interrupted half-dry commits exactly as the screen showed it, soft rim
 *   and all, instead of snapping crisp.
 *
 * ```
 * byte 0   : version u8 (= 1)
 * bytes 1+ : zlib{ scheduleCount u32 | schedule i32 × n
 *                | hasCrop u8 | crop i32 × 4      (present only when hasCrop)
 *                | freezeCount u32 | freeze f32 × m }
 * ```
 */
object WetStateCodec {

    const val VERSION: Byte = 1

    /** Bounds a corrupt count; a real schedule is hundreds of ticks, not millions. */
    private const val MAX_ENTRIES = 4_000_000

    data class WetState(
        val schedule: IntArray = IntArray(0),
        /** left, top, right, bottom — or null when the stroke never went wet. */
        val crop: IntArray? = null,
        val dryFreeze: FloatArray? = null,
    ) {
        override fun equals(other: Any?): Boolean = other is WetState &&
            other.schedule.contentEquals(schedule) &&
            (other.crop?.contentEquals(crop) ?: (crop == null)) &&
            (other.dryFreeze?.contentEquals(dryFreeze) ?: (dryFreeze == null))

        override fun hashCode(): Int {
            var h = schedule.contentHashCode()
            h = 31 * h + (crop?.contentHashCode() ?: 0)
            h = 31 * h + (dryFreeze?.contentHashCode() ?: 0)
            return h
        }
    }

    fun encode(state: WetState): ByteArray {
        require(state.crop == null || state.crop.size == 4) { "A crop is 4 ints" }
        val freeze = state.dryFreeze
        val size = 4 + state.schedule.size * 4 +
            1 + (if (state.crop != null) 16 else 0) +
            4 + (freeze?.size ?: 0) * 4

        val payload = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(state.schedule.size)
        for (v in state.schedule) payload.putInt(v)
        payload.put(if (state.crop != null) 1 else 0)
        state.crop?.forEach { payload.putInt(it) }
        payload.putInt(freeze?.size ?: 0)
        freeze?.forEach { payload.putFloat(it) }

        return byteArrayOf(VERSION) + Deflate.compress(payload.array())
    }

    /**
     * Null for anything unreadable.
     *
     * The degradation is graceful and worth knowing: without wet state, the
     * stroke still replays — as a fully dried wash rather than the frozen one the
     * user saw. Losing the nuance beats losing the stroke.
     */
    fun decode(bytes: ByteArray?): WetState? {
        if (bytes == null || bytes.size < 2) return null
        if (bytes[0] != VERSION) return null

        val payload = Deflate.inflate(bytes.copyOfRange(1, bytes.size)) ?: return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            val scheduleCount = buffer.int
            if (scheduleCount < 0 || scheduleCount > MAX_ENTRIES) return null
            if (buffer.remaining() < scheduleCount * 4) return null
            val schedule = IntArray(scheduleCount) { buffer.int }

            if (buffer.remaining() < 1) return null
            val crop = if (buffer.get().toInt() != 0) {
                if (buffer.remaining() < 16) return null
                IntArray(4) { buffer.int }
            } else {
                null
            }

            if (buffer.remaining() < 4) return null
            val freezeCount = buffer.int
            if (freezeCount < 0 || freezeCount > MAX_ENTRIES) return null
            if (buffer.remaining() < freezeCount * 4) return null
            val freeze = if (freezeCount > 0) FloatArray(freezeCount) { buffer.float } else null

            WetState(schedule, crop, freeze)
        } catch (t: Throwable) {
            null
        }
    }
}
