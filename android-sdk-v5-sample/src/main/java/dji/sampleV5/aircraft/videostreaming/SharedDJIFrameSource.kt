package dji.sampleV5.aircraft.videostreaming

import android.util.Log
import android.view.Surface
import dji.sampleV5.aircraft.webrtc.FrameMetadata
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

    @Volatile private var currentSurface: Surface? = null
    @Volatile private var surfaceWidth: Int = 0
    @Volatile private var surfaceHeight: Int = 0

    @Volatile var targetFps: Int = 30

    private val metadataListeners = ConcurrentHashMap<String, (FrameMetadata) -> Unit>()

    var srtStreamer: SrtStreamer? = null
        set(value) {
            field?.onSurfaceReady = null
            field?.onSurfaceDestroyed = null
            field = value
            value?.onSurfaceReady = { surface, w, h -> onEncoderSurfaceReady(surface, w, h) }
            value?.onSurfaceDestroyed = { surface -> onEncoderSurfaceDestroyed(surface) }
        }

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

        val oldSurface = currentSurface
        if (isCapturing.get() && oldSurface != null) {
            runCatching { cameraStreamManager.removeCameraStreamSurface(oldSurface) }
        }

        activeCameraIndex = resolved
        Log.i(TAG, "Active camera index changed to: $resolved")

        if (isCapturing.get() && oldSurface != null) {
            attachSurface(oldSurface, surfaceWidth, surfaceHeight)
        }
    }

    @Synchronized
    private fun onEncoderSurfaceReady(surface: Surface, width: Int, height: Int) {
        currentSurface?.let { old ->
            if (old !== surface) {
                runCatching { cameraStreamManager.removeCameraStreamSurface(old) }
            }
        }
        currentSurface = surface
        surfaceWidth = width
        surfaceHeight = height

        if (isCapturing.get()) {
            attachSurface(surface, width, height)
        }
    }

    @Synchronized
    private fun onEncoderSurfaceDestroyed(surface: Surface) {
        runCatching { cameraStreamManager.removeCameraStreamSurface(surface) }
        if (currentSurface === surface) {
            currentSurface = null
            surfaceWidth = 0
            surfaceHeight = 0
        }
    }

    private fun attachSurface(surface: Surface, width: Int, height: Int) {
        try {
            runCatching { cameraStreamManager.enableStream(activeCameraIndex, true) }
            cameraStreamManager.putCameraStreamSurface(
                activeCameraIndex,
                surface,
                width,
                height,
                ICameraStreamManager.ScaleType.CENTER_INSIDE
            )
            Log.i(TAG, "Attached encoder Surface (${width}x${height}) to $activeCameraIndex")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach Surface to DJI camera: ${e.message}", e)
        }
    }

    fun addMetadataListener(clientId: String, listener: (FrameMetadata) -> Unit) {
        metadataListeners[clientId] = listener
    }

    fun removeMetadataListener(clientId: String) {
        metadataListeners.remove(clientId)
    }

    @Synchronized
    fun startCapture() {
        if (isCapturing.compareAndSet(false, true)) {
            runCatching { cameraStreamManager.enableStream(activeCameraIndex, true) }
            currentSurface?.let { attachSurface(it, surfaceWidth, surfaceHeight) }
            Log.i(TAG, "Started DJI Surface Frame Source on $activeCameraIndex")
        }
    }

    @Synchronized
    fun stopCapture() {
        if (isCapturing.compareAndSet(true, false)) {
            currentSurface?.let { runCatching { cameraStreamManager.removeCameraStreamSurface(it) } }
            Log.i(TAG, "Stopped DJI Surface Frame Source")
        }
    }

    fun changeFrameRate(fps: Int) {
        targetFps = fps.coerceIn(1, 60)
    }

    fun dispose() {
        stopCapture()
        srtStreamer = null
        try {
            cameraStreamManager.removeAvailableCameraUpdatedListener(availableCameraListener)
        } catch (e: Exception) {}
        metadataListeners.clear()
    }
}