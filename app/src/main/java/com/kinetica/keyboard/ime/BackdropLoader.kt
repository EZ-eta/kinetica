package com.kinetica.keyboard.ime

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import com.kinetica.keyboard.settings.BackdropStore
import java.util.concurrent.Executors

/**
 * Holds the background image the keyboard draws, read again only when its stamp changes: a
 * screen-sized decode is tens of ms, too slow for every settings change or input view.
 */
internal class BackdropLoader(private val context: Context, private val main: Handler) {

    // Its own thread: the decode thread serves typing and the db thread Room.
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "kinetica-backdrop") }

    private var stamp: String? = null

    /** The image for the current stamp, or null for none, a missing file or a read in flight. */
    var bitmap: Bitmap? = null
        private set

    /** Reads the image again if [current] differs from the last stamp; [onReady] runs on main. */
    fun refresh(current: String, onReady: () -> Unit) {
        if (current == stamp) return
        stamp = current
        if (current.isEmpty()) {
            bitmap = null
            onReady()
            return
        }
        io.execute {
            val read = BackdropStore.load(context)
            main.post {
                // A newer pick while this one was read wins.
                if (stamp == current) {
                    bitmap = read
                    onReady()
                }
            }
        }
    }

    fun shutdown() {
        io.shutdown()
    }
}
