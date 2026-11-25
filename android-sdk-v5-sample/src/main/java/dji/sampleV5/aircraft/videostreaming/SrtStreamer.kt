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

/** Parâmetros ajustáveis em tempo de execução. null = usa o padrão (preset / biblioteca). */
data class SrtParams(
    val kbps: Int? = null,
    val fps: Int? = null,
    val iframeSec: Int = 1,
    val latencyMs: Int? = null,
    val retransmitOverheadPct: Int? = null,
    val serverSilenceMs: Long = 0
)

class SrtStreamer(private val context: Context) {

    private val TAG = "SrtStreamer"
    private val main = Handler(Looper.getMainLooper())

    // Estado de controle: só na thread principal
    private var config: VideoStreamConfig = VideoStreamConfig.hd()
    private var params = SrtParams()
    private var url: String? = null
    private var wanted = false
    private var lastBytes = 0L

    @Volatile private var session: Session? = null

    // true = o app deve entregar frames (o encoder nasce no primeiro frame, antes da conexão)
    val isStreaming: Boolean get() = session != null

    private inner class Session : ConnectChecker, GetVideoData {
        val client = SrtClient(this)
        val encoder = VideoEncoder(this)
        @Volatile var live = false
        @Volatile var connected = false
        @Volatile var sizeRequested = false
        @Volatile var sizeChanged = false
        @Volatile var w = 0
        @Volatile var h = 0
        var connectCalled = false


        private fun current() = session === this

        override fun onConnectionStarted(url: String) {
            if (current()) Log.i(TAG, "SRT Connection Started: $url")
        }
        override fun onConnectionSuccess() { main.post { if (current()) onConnected(this) } }
        override fun onConnectionFailed(reason: String) { main.post { if (current()) onLost("failed: $reason") } }
        override fun onDisconnect() { main.post { if (current()) onLost("disconnected") } }
        override fun onAuthError() { main.post { if (current()) onLost("auth error") } }
        override fun onNewBitrate(bitrate: Long) {}
        override fun onAuthSuccess() {}

        override fun onVideoFormat(mediaFormat: MediaFormat) {}
        override fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
            if (!current()) return
            client.setVideoInfo(sps, pps, vps)
            main.post { if (current()) connectIfNeeded(this) }   // pode ser chamado mais de uma vez; connectIfNeeded protege
        }
        override fun getVideoData(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (current() && connected) client.sendVideo(h264Buffer, info)
        }
    }

    private val retry = Runnable { if (wanted && session == null) openSession() }

    private val statsTick = object : Runnable {
        override fun run() {
            val s = session
            if (s != null && s.connected) {
                val c = s.client
                val bytes = c.bytesSend
                val kbps = ((bytes - lastBytes) * 8 / 1000).coerceAtLeast(0)
                lastBytes = bytes
                Log.i(TAG, "STATS rtt_ms=${c.rtt / 1000} lost=${c.packetsLost} lostUniq=${c.packetsLostUnique} " +
                        "droppedV=${c.droppedVideoFrames} sentV=${c.sentVideoFrames} kbps=$kbps queue=${c.getItemsInCache()}")
            } else {
                lastBytes = 0
            }
            main.postDelayed(this, 1000)
        }
    }

    init { main.postDelayed(statsTick, 1000) }

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

    /** Troca os parâmetros e reinicia a sessão (se estiver transmitindo). */
    fun applyParams(p: SrtParams) {
        main.post {
            params = p
            Log.i(TAG, "PARAMS $p")
            if (wanted) openSession()
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        main.post { wanted = false; closeSession() }
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
        val fps = params.fps ?: config.fps
        val bps = params.kbps?.times(1000) ?: config.copy(width = w, height = h).senderBitrateBps()
        Log.i(TAG, "CONFIG w=$w h=$h fps=$fps kbps=${bps / 1000} iframe=${params.iframeSec}")
        try {
            val ok = s.encoder.prepareVideoEncoder(w, h, fps, bps, 0, params.iframeSec, FormatVideoEncoder.YUV420Dynamical)
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
        params.latencyMs?.let { s.client.setLatency(it) }                          // em ms
        params.retransmitOverheadPct?.let { s.client.setRetransmitOverhead(it) }   // % (padrão 25)
        s.client.setServerSilenceTimeout(params.serverSilenceMs)                   // 0 = desligado
        Log.i(TAG, "Connecting SRT stream to $u latency=${params.latencyMs ?: "default"}")
        s.client.connect(u)
        main.postDelayed({ if (session === s && !s.connected) onLost("connect timeout") }, 8000)
    }

    private fun onConnected(s: Session) {
        s.connected = true
        Log.i(TAG, "SRT Connected successfully")
        runCatching { s.encoder.requestKeyframe() }   // se não compilar, apague esta linha
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