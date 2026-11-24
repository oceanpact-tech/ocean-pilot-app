package dji.sampleV5.aircraft.videostreaming

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.Frame
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.encoder.video.GetVideoData
import com.pedro.encoder.video.VideoEncoder
import com.pedro.srt.srt.SrtClient
import java.nio.ByteBuffer

class SrtStreamer(private val context: Context) {

    private val TAG = "SrtStreamer"
    private val main = Handler(Looper.getMainLooper())

    // Estado de controle: só na thread principal
    private var config: VideoStreamConfig = VideoStreamConfig.hd()
    private var url: String? = null
    private var wanted = false

    @Volatile private var session: Session? = null

    // true = o app deve entregar frames (o encoder nasce no primeiro frame, antes da conexão)
    val isStreaming: Boolean get() = session != null

    private inner class Session : ConnectChecker, GetVideoData {
        val client = SrtClient(this)
        val encoder = VideoEncoder(this)
        @Volatile var live = false          // encoder rodando
        @Volatile var connected = false     // SRT conectado
        @Volatile var sizeRequested = false
        @Volatile var sizeChanged = false
        @Volatile var w = 0
        @Volatile var h = 0
        var connectCalled = false           // só main

        private fun current() = session === this

        // ConnectChecker
        override fun onConnectionStarted(url: String) {
            if (current()) Log.i(TAG, "SRT Connection Started: $url")
        }
        override fun onConnectionSuccess() { main.post { if (current()) onConnected(this) } }
        override fun onConnectionFailed(reason: String) { main.post { if (current()) onLost("failed: $reason") } }
        override fun onDisconnect() { main.post { if (current()) onLost("disconnected") } }
        override fun onAuthError() { main.post { if (current()) onLost("auth error") } }
        override fun onNewBitrate(bitrate: Long) {}
        override fun onAuthSuccess() {}

        // GetVideoData
        override fun onVideoFormat(mediaFormat: MediaFormat) {}
        override fun onSpsPpsVps(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
            if (!current()) return
            client.setVideoInfo(sps, pps ?: return, vps)
            main.post { if (current()) connectIfNeeded(this) }   // só conecta com o SPS/PPS já entregue
        }
        override fun getVideoData(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (current() && connected) client.sendVideo(h264Buffer, info)
        }
    }

    private val retry = Runnable { if (wanted && session == null) openSession() }

    // ---------------- API pública (qualquer thread) ----------------

    fun prepareVideo(cfg: VideoStreamConfig): Boolean {
        main.post { config = cfg }
        return true
    }

    fun startStream(srtUrl: String) {
        main.post {
            val changed = srtUrl != url
            url = srtUrl
            wanted = true
            if (session == null || changed) openSession()
        }
    }

    fun stopStream() {
        main.post {
            wanted = false
            main.removeCallbacks(retry)
            closeSession()
            Log.i(TAG, "Stopped SRT stream")
        }
    }

    fun feedCustomFrame(nv21ByteArray: ByteArray, timestampNs: Long, width: Int, height: Int) {
        val s = session ?: return

        if (!s.live) {
            // O encoder nasce com o tamanho real do frame: a DJI entrega o tamanho nativo e aqui não há escala.
            if (!s.sizeRequested) {
                s.sizeRequested = true
                main.post { if (session === s) startEncoder(s, width, height) }
            }
            return
        }

        if ((width != s.w || height != s.h) && !s.sizeChanged) {
            s.sizeChanged = true
            main.post { if (session === s) onLost("tamanho do frame mudou: ${s.w}x${s.h} -> ${width}x${height}") }
            return
        }

        try {
            s.encoder.inputYUVData(Frame(nv21ByteArray, 0, nv21ByteArray.size, timestampNs / 1000))
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding NV21 frame to encoder", e)
        }
    }

    // ---------------- Controle (só na main) ----------------

    private fun openSession() {
        closeSession()
        val s = Session()
        session = s
        runCatching { s.client.setOnlyVideo(true) }
        Log.i(TAG, "Sessão nova, aguardando o primeiro frame da câmera")
    }

    private fun startEncoder(s: Session, w: Int, h: Int) {
        val cfg = config
        val bps = cfg.copy(width = w, height = h).senderBitrateBps()
        Log.i(TAG, "CONFIG w=$w h=$h fps=${cfg.fps} kbps=${bps / 1000} iframe=1")
        try {
            val ok = s.encoder.prepareVideoEncoder(w, h, cfg.fps, bps, 0, 1, FormatVideoEncoder.YUV420Dynamical)
            if (!ok) { onLost("prepare failed"); return }
            s.w = w
            s.h = h
            s.encoder.start()
            s.live = true
        } catch (e: Exception) {
            onLost("encoder: ${e.message}")
        }
    }

    private fun connectIfNeeded(s: Session) {
        if (s.connectCalled) return
        val u = url ?: return
        s.connectCalled = true
        Log.i(TAG, "Connecting SRT stream to $u")
        s.client.connect(u)
        main.postDelayed({ if (session === s && !s.connected) onLost("connect timeout") }, 8000)
    }

    private fun onConnected(s: Session) {
        s.connected = true
        Log.i(TAG, "SRT Connected successfully")
    }

    private fun closeSession() {
        val s = session ?: return
        session = null                // callbacks dessa sessão passam a ser ignorados
        s.live = false
        s.connected = false
        runCatching { s.encoder.stop() }
        runCatching { s.client.disconnect() }
    }

    private fun onLost(reason: String) {
        Log.e(TAG, "SRT session lost: $reason")
        closeSession()
        if (wanted) {
            main.removeCallbacks(retry)
            main.postDelayed(retry, 1500)
        }
    }
}