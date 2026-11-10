package dji.sampleV5.aircraft.webrtc

/**
 * Configuration options for WebRTC media streaming.
 */
data class WebRTCMediaOptions(
    val mediaStreamId: String = "DJI_DRONE_STREAM",
    val videoTrackId: String = "DJI_VIDEO_TRACK",
    val videoResolutionWidth: Int = 1280,
    val videoResolutionHeight: Int = 720,
    val fps: Int = 30,
    val videoBitrate: Int = 4_000_000,
    val videoCodec: String = "H264"     // H264 High Profile for best quality
) {
    val usesSourceResolution: Boolean
        get() = videoResolutionWidth <= 0 || videoResolutionHeight <= 0

    /**
     * Cap sender bitrate according to target resolution.
     */
    fun senderBitrateBps(): Int = when {
        usesSourceResolution -> minOf(videoBitrate, 3_500_000)
        videoResolutionWidth >= 1920 || videoResolutionHeight >= 1080 -> minOf(videoBitrate, 3_500_000)
        videoResolutionWidth >= 1280 || videoResolutionHeight >= 720 -> minOf(videoBitrate, 2_000_000)
        else -> minOf(videoBitrate, 1_000_000)
    }

    companion object {
        /** Preserve the source frame size and skip app-side scaling. */
        fun native() = WebRTCMediaOptions(
            videoResolutionWidth = 0,
            videoResolutionHeight = 0,
            fps = 30,
            videoBitrate = 6_000_000,
            videoCodec = "H264"
        )

        /** 1920x1080 @ 6 Mbps — best quality for detection */
        fun fullHD() = WebRTCMediaOptions(
            videoResolutionWidth = 1920,
            videoResolutionHeight = 1080,
            fps = 30,
            videoBitrate = 6_000_000,
            videoCodec = "H264"
        )

        /** 1280x720 @ 3.5 Mbps — crisp 720p stream */
        fun hd() = WebRTCMediaOptions(
            videoResolutionWidth = 1280,
            videoResolutionHeight = 720,
            fps = 30,
            videoBitrate = 3_500_000,
            videoCodec = "H264"
        )

        /** 640x480 @ 1.5 Mbps — low bandwidth fallback */
        fun sd() = WebRTCMediaOptions(
            videoResolutionWidth = 640,
            videoResolutionHeight = 480,
            fps = 10,
            videoBitrate = 1_500_000,
            videoCodec = "H264"
        )
    }
}
