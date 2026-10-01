package com.i3u8.sleepdesk.audio

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/**
 * Single-instance AAC/M4A clip player. Starting another clip (or pause/stop)
 * releases the previous MediaPlayer. Call [release] when the UI is dismissed.
 */
class ClipPlayer(context: Context) {

    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var playingPath: String? = null
    private var listener: Listener? = null

    interface Listener {
        fun onPlayingChanged(path: String?, playing: Boolean)
        fun onError(message: String)
        fun onCompleted(path: String)
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    fun isPlaying(path: String? = null): Boolean {
        val p = player ?: return false
        return try {
            if (!p.isPlaying) false
            else if (path == null) true else playingPath == path
        } catch (_: Exception) {
            false
        }
    }

    fun currentPath(): String? = playingPath

    fun play(file: File) {
        if (!file.exists() || file.length() == 0L) {
            stopInternal(notify = true)
            listener?.onError("missing")
            return
        }
        val abs = file.absolutePath
        if (playingPath == abs && player?.isPlaying == true) {
            pause()
            return
        }
        stopInternal(notify = true)
        try {
            val mp = MediaPlayer().apply {
                setDataSource(abs)
                setOnCompletionListener {
                    playingPath = null
                    listener?.onCompleted(abs)
                    listener?.onPlayingChanged(null, false)
                    releaseQuiet(this)
                    if (player === this) player = null
                }
                setOnErrorListener { _, _, _ ->
                    listener?.onError("playback")
                    stopInternal(notify = true)
                    true
                }
                prepare()
                start()
            }
            player = mp
            playingPath = abs
            listener?.onPlayingChanged(abs, true)
        } catch (t: Exception) {
            stopInternal(notify = false)
            listener?.onError(t.message ?: "playback")
            listener?.onPlayingChanged(null, false)
        }
    }

    fun pause() {
        val p = player ?: return
        try {
            if (p.isPlaying) p.pause()
        } catch (_: Exception) {
        }
        listener?.onPlayingChanged(playingPath, false)
    }

    fun resume() {
        val p = player ?: return
        try {
            if (!p.isPlaying) {
                p.start()
                listener?.onPlayingChanged(playingPath, true)
            }
        } catch (t: Exception) {
            listener?.onError(t.message ?: "playback")
        }
    }

    fun toggle(file: File) {
        val abs = file.absolutePath
        if (playingPath == abs && player != null) {
            try {
                if (player!!.isPlaying) pause() else resume()
            } catch (_: Exception) {
                play(file)
            }
        } else {
            play(file)
        }
    }

    fun stop() = stopInternal(notify = true)

    fun release() {
        stopInternal(notify = false)
        listener = null
    }

    private fun stopInternal(notify: Boolean) {
        val path = playingPath
        val p = player
        player = null
        playingPath = null
        releaseQuiet(p)
        if (notify) listener?.onPlayingChanged(path, false)
    }

    private fun releaseQuiet(mp: MediaPlayer?) {
        try {
            mp?.reset()
            mp?.release()
        } catch (_: Exception) {
        }
    }
}
