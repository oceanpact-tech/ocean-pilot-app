package dji.sampleV5.aircraft.videostreaming

import android.util.Log
import dji.sampleV5.aircraft.webrtc.FrameMetadata
import dji.sampleV5.aircraft.webrtc.TelemetryProvider
import dji.sampleV5.aircraft.videostreaming.SrtStreamer
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SharedDJIFrameSource(
    private val preferredCameraIndex: ComponentIndexType,
    private val droneName: String
) {
    companion object {
        private const val TAG = "SharedDJIFrameSource"
        private val CAMERA_PREFERENCE = listOf(
            ComponentIndexType.LEFT_OR_MAIN,
            ComponentIndexType.FPV,
            ComponentIndexType.RIGHT,
            ComponentIndexType.UP
        )
        private val NON_CAMERA_INDICES = setOf(ComponentIndexType.PORT_4)
    }

    @Volatile
    private var activeCameraIndex: ComponentIndexType = preferredCameraIndex
    private val isCapturing = AtomicBoolean(false)

    // Link to our new SRT hardware encoder
    var srtStreamer: SrtStreamer? = null

    // Keep telemetry listeners
    private val metadataListeners = ConcurrentHashMap<String, (FrameMetadata) -> Unit>()

    @Volatile var targetFps: Int = 30
    @Volatile private var frameIntervalNs: Long = 1_000_000_000L / 30L

    private val lastSentTimestampNs = AtomicLong(0L)
    private val frameCounter = AtomicLong(0)

    // Single-worker executor so DJI's camera callback thread is never blocked
    private val frameProcessorExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { r -> Thread(r, "DJI-SRT-FrameProc").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val cameraStreamManager: ICameraStreamManager by lazy {
        MediaDataCenter.getInstance().cameraStreamManager
    }

    private val availableCameraListener = object : ICameraStreamManager.AvailableCameraUpdatedListener {
        override fun onAvailableCameraUpdated(availableCameraList: MutableList<ComponentIndexType>) {
            onAvailableCamerasChanged(availableCameraList)
        }
        override fun onCameraStreamEnableUpdate(cameraStreamEnableMap: MutableMap<ComponentIndexType, Boolean>) {}
    }

    init {
        try {
            cameraStreamManager.addAvailableCameraUpdatedListener(availableCameraListener)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register available-camera listener: ${e.message}")
        }
    }

    private fun isMatrice400(): Boolean =
        ProductKey.KeyProductType.create().get(ProductType.UNKNOWN) == ProductType.DJI_MATRICE_400

    private fun pickCameraIndex(available: List<ComponentIndexType>): ComponentIndexType? {
        val cameras = if (isMatrice400()) available.filterNot { it in NON_CAMERA_INDICES } else available
        if (cameras.isEmpty()) return null
        if (cameras.contains(preferredCameraIndex)) return preferredCameraIndex
        for (candidate in CAMERA_PREFERENCE) {
            if (cameras.contains(candidate)) return candidate
        }
        return cameras.first()
    }

    @Synchronized
    private fun onAvailableCamerasChanged(available: List<ComponentIndexType>) {
        val resolved = pickCameraIndex(available) ?: return
        if (resolved == activeCameraIndex) return

        activeCameraIndex = resolved
        Log.i(TAG, "Active camera index changed to: $resolved")

        if (isCapturing.get()) {
            try {
                cameraStreamManager.removeFrameListener(frameListener)
                cameraStreamManager.addFrameListener(
                    activeCameraIndex,
                    ICameraStreamManager.FrameFormat.NV21,
                    frameListener
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-attach frame listener: ${e.message}", e)
            }
        }
    }

    private val frameListener = object : ICameraStreamManager.CameraFrameListener {
        override fun onFrame(
            frameData: ByteArray,
            offset: Int,
            length: Int,
            width: Int,
            height: Int,
            format: ICameraStreamManager.FrameFormat
        ) {
            if (!isCapturing.get() || srtStreamer?.isStreaming != true) return

            val timestampNs = System.nanoTime()
            val previousSent = lastSentTimestampNs.get()

            // FPS limiting
            if (previousSent != 0L && (timestampNs - previousSent) < frameIntervalNs) {
                return
            }
            lastSentTimestampNs.set(timestampNs)

            // Extract just the active bytes
            val nv21Bytes = if (offset == 0 && length == frameData.size) {
                frameData.clone()
            } else {
                frameData.copyOfRange(offset, (offset + length).coerceAtMost(frameData.size))
            }

            // Offload encoding to prevent dropping DJI SDK connection
            frameProcessorExecutor.execute {
                processFrameAsync(nv21Bytes, width, height, timestampNs)
            }
        }
    }

    private fun processFrameAsync(frameData: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (!isCapturing.get()) return

        try {
            val frameNumber = frameCounter.incrementAndGet()

            // 1. Capture and broadcast synced telemetry
            if (metadataListeners.isNotEmpty()) {
                val metadata = TelemetryProvider.captureMetadata(
                    frameNumber = frameNumber,
                    timestampNs = timestampNs,
                    frameWidth = width,
                    frameHeight = height,
                    droneName = droneName
                )
                metadataListeners.values.forEach { it.invoke(metadata) }
            }

            // 2. Feed raw NV21 bytes into the SRT hardware encoder
            srtStreamer?.feedCustomFrame(frameData, timestampNs, width, height)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame: ${e.message}", e)
        }
    }

    fun addMetadataListener(clientId: String, listener: (FrameMetadata) -> Unit) {
        metadataListeners[clientId] = listener
    }

    fun removeMetadataListener(clientId: String) {
        metadataListeners.remove(clientId)
    }

    fun startCapture() {
        if (isCapturing.compareAndSet(false, true)) {
            lastSentTimestampNs.set(0L)
            runCatching { cameraStreamManager.enableStream(activeCameraIndex, true) }
            cameraStreamManager.addFrameListener(
                activeCameraIndex,
                ICameraStreamManager.FrameFormat.NV21,
                frameListener
            )
            Log.i(TAG, "Started DJI Frame Source on $activeCameraIndex")
        }
    }

    fun stopCapture() {
        if (isCapturing.compareAndSet(true, false)) {
            cameraStreamManager.removeFrameListener(frameListener)
            Log.i(TAG, "Stopped DJI Frame Source")
        }
    }

    fun changeFrameRate(fps: Int) {
        targetFps = fps.coerceIn(1, 60)
        frameIntervalNs = 1_000_000_000L / targetFps.toLong()
    }

    fun dispose() {
        stopCapture()
        try {
            cameraStreamManager.removeAvailableCameraUpdatedListener(availableCameraListener)
        } catch (e: Exception) {}
        metadataListeners.clear()
        frameProcessorExecutor.shutdownNow()
    }
}