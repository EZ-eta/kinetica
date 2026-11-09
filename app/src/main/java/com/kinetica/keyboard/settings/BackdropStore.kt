// The framework EXIF reader, not androidx's: one tag read from a file the user picked, and no new
// dependency for it.
@file:SuppressLint("ExifInterface")

package com.kinetica.keyboard.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.kinetica.keyboard.ui.BackdropFit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The keyboard's background image: a copy in app storage, never the picked file itself, so the
 * keyboard needs no read permission and keeps the image if the original is deleted. Shrunk on
 * import to the screen's long edge and turned upright.
 *
 * A pick lands in a pending file; the crop screen's Save makes it the image, its Cancel drops it,
 * so the image in use never changes before its cuts are chosen.
 *
 * The night look has an image of its own, in its own file; `night` picks it throughout.
 */
object BackdropStore {

    private const val FILE = "keyboard_backdrop.jpg"
    private const val NIGHT_FILE = "keyboard_backdrop_night.jpg"
    private const val PENDING = "keyboard_backdrop_pending.jpg"

    /** JPEG at 90: a photo stays sharp under translucent keys at a fraction of PNG's size. */
    private const val QUALITY = 90

    fun file(context: Context, night: Boolean = false): File = File(context.filesDir, if (night) NIGHT_FILE else FILE)

    fun pendingFile(context: Context): File = File(context.filesDir, PENDING)

    /** Whether an image is set and its file is there; a restored stamp without one is not. */
    fun isSet(context: Context, prefs: SharedPreferences, night: Boolean = false): Boolean =
        !LookSets.view(prefs, night).getString(Prefs.BACKDROP, "").isNullOrEmpty() && file(context, night).exists()

    /**
     * Copies [uri] into the pending file, at most [maxLong] px on its long edge. Slow: off the
     * main thread. The crop screen takes it from there.
     */
    @Throws(IOException::class)
    fun import(context: Context, uri: Uri, maxLong: Int) {
        val resolver = context.contentResolver
        fun open() = resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
        // A bounds-only decode always returns null; the size is in the options.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")
        val degrees = try {
            open().use {
                BackdropFit.degreesFor(
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
                )
            }
        } catch (e: IOException) {
            // No readable EXIF (a PNG, a screenshot): taken as upright.
            0
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = BackdropFit.sampleSize(bounds.outWidth, bounds.outHeight, maxLong)
        }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("cannot decode $uri")
        // The power-of-two step leaves up to twice the screen; the keyboard holds this in memory.
        val (sw, sh) = BackdropFit.scaledSize(decoded.width, decoded.height, maxLong)
        val sized = if (sw == decoded.width && sh == decoded.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, sw, sh, true).also { if (it !== decoded) decoded.recycle() }
        }
        val upright = if (degrees == 0) {
            sized
        } else {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(sized, 0, 0, sized.width, sized.height, m, true)
                .also { if (it !== sized) sized.recycle() }
        }
        val tmp = File(context.filesDir, "$PENDING.tmp")
        try {
            FileOutputStream(tmp).use {
                if (!upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)) throw IOException("cannot write")
            }
        } finally {
            upright.recycle()
        }
        replace(tmp, pendingFile(context))
    }

    /** The pending image becomes the one in use; false when there is none or it cannot move. */
    fun commitPending(context: Context, night: Boolean = false): Boolean {
        val pending = pendingFile(context)
        if (!pending.exists()) return false
        return try {
            replace(pending, file(context, night))
            true
        } catch (e: IOException) {
            false
        }
    }

    fun dropPending(context: Context) {
        pendingFile(context).delete()
    }

    // A rename, so the keyboard never reads a half-written image.
    @Throws(IOException::class)
    private fun replace(from: File, to: File) {
        if (!from.renameTo(to)) {
            from.delete()
            throw IOException("cannot replace $to")
        }
    }

    /** The image in use as stored, for a backup; null when there is none or it cannot be read. */
    fun bytes(context: Context, night: Boolean = false): ByteArray? = try {
        file(context, night).takeIf { it.exists() }?.readBytes()
    } catch (e: IOException) {
        null
    }

    /**
     * A backed-up image becomes the one in use. Checked as an image first, so a damaged record
     * never replaces a good one. Slow: off the main thread.
     */
    @Throws(IOException::class)
    fun restore(context: Context, jpeg: ByteArray, night: Boolean = false) {
        // A bounds-only decode always returns null; the size is in the options.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")
        val tmp = File(context.filesDir, "${file(context, night).name}.restore")
        try {
            tmp.writeBytes(jpeg)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        replace(tmp, file(context, night))
    }

    /**
     * The day image copied as the night one, when the night look starts as a copy of the day.
     * False when there is none or it cannot be copied: the night look then starts without one.
     */
    fun copyDayToNight(context: Context): Boolean {
        val day = file(context)
        if (!day.exists()) return false
        val tmp = File(context.filesDir, "$NIGHT_FILE.copy")
        return try {
            day.copyTo(tmp, overwrite = true)
            replace(tmp, file(context, night = true))
            true
        } catch (e: IOException) {
            tmp.delete()
            false
        }
    }

    fun remove(context: Context, night: Boolean = false) {
        file(context, night).delete()
        dropPending(context)
    }

    /**
     * The image for drawing, or null when there is none or it cannot be read. RGB_565: a JPEG has
     * no alpha, and it halves a screen-sized bitmap's memory in the keyboard's process.
     */
    fun load(context: Context, night: Boolean = false): Bitmap? {
        val f = file(context, night)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
