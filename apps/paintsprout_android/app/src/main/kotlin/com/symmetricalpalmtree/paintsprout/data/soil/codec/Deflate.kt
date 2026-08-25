package com.symmetricalpalmtree.paintsprout.data.soil.codec

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * zlib, with the one guard that turns a hang into an error.
 *
 * Every blob in a document is bytes that may have been damaged by a bad sector, a
 * torn write or a version skew, and the decoder is where that damage arrives. The
 * rule worth stating plainly:
 *
 * > **Bail the inflate loop on any zero-progress round.** A corrupt zlib header —
 * > an FDICT bit set, for instance — makes `inflate()` return 0 bytes *forever*
 * > without ever reporting "finished". That is not an exception, it is a spin, on
 * > the page-load path, which is an ANR the user cannot escape. Notesprout hit
 * > this for real.
 */
internal object Deflate {

    /** Nothing here is ever big enough that a corrupt length should be believed. */
    const val MAX_INFLATED_BYTES = 64 * 1024 * 1024

    fun compress(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 2 + 32)
            val buffer = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n == 0 && deflater.needsInput()) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /**
     * Inflates, or returns null. Never throws, and never spins.
     *
     * A null here means "this blob is not readable" — the caller degrades to a
     * missing stroke or a missing mask, never to an unopenable page.
     */
    fun inflate(bytes: ByteArray, limit: Int = MAX_INFLATED_BYTES): ByteArray? {
        if (bytes.isEmpty()) return null
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 3 + 32)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    return null
                }
                if (n == 0) {
                    // The guard. Finished is the only legitimate way for this loop
                    // to end; anything else that produces nothing will produce
                    // nothing again, forever.
                    if (inflater.finished()) break
                    return null
                }
                if (out.size() + n > limit) return null
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } catch (t: Throwable) {
            return null
        } finally {
            inflater.end()
        }
    }
}
