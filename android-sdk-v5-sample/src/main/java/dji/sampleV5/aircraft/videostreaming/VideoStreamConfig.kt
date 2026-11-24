package dji.sampleV5.aircraft.videostreaming

/**
 * Configuration options for video streaming.
 */
data class VideoStreamConfig(
    val mediaStreamId: String = StreamOptions.MEDIA_STREAM_ID,
    val videoTrackId:  String = StreamOptions.VIDEO_TRACK_ID,
    val width:         Int    = StreamOptions.HD_WIDTH,
    val height:        Int    = StreamOptions.HD_HEIGHT,
    val fps:           Int    = StreamOptions.DEFAULT_FPS,
    val bitrateBps:    Int    = StreamOptions.DEFAULT_BITRATE_BPS,
    val codec:         String = StreamOptions.CODEC       
) {
    val usesSourceResolution: Boolean
        get() = width <= 0 || height <= 0

    /**
     * Cap sender bitrate according to target resolution.
     */
    fun senderBitrateBps(): Int = when {
        usesSourceResolution -> minOf(bitrateBps, StreamOptions.AUTO_BITRATE_BPS)
        width >= StreamOptions.FULL_HD_WIDTH || height >= StreamOptions.FULL_HD_HEIGHT -> minOf(bitrateBps, StreamOptions.FULL_HD_BITRATE_BPS)
        width >= StreamOptions.HD_WIDTH      || height >= StreamOptions.HD_HEIGHT      -> minOf(bitrateBps, StreamOptions.HD_BITRATE_BPS)
        else -> minOf(bitrateBps, StreamOptions.SD_BITRATE_BPS)
    }

    companion object {

        /** Preserve the source frame size and skip app-side scaling. */
        fun native() = VideoStreamConfig(
            width      = StreamOptions.NATIVE_WIDTH,
            height     = StreamOptions.NATIVE_HEIGHT,
            fps        = StreamOptions.DEFAULT_FPS,
            bitrateBps = StreamOptions.AUTO_BITRATE_BPS,
            codec      = StreamOptions.CODEC 
        )

        /** 1920x1080 @ 6 Mbps — best quality for detection */
        fun fullHD() = VideoStreamConfig(
            width      = StreamOptions.FULL_HD_WIDTH,
            height     = StreamOptions.FULL_HD_HEIGHT,
            fps        = StreamOptions.DEFAULT_FPS,
            bitrateBps = StreamOptions.FULL_HD_BITRATE_BPS,
            codec      = StreamOptions.CODEC 
        )

        /** 1280x720 @ 3.5 Mbps — crisp 720p stream */
        fun hd() = VideoStreamConfig(
            width      = StreamOptions.HD_WIDTH,
            height     = StreamOptions.HD_HEIGHT,
            fps        = StreamOptions.DEFAULT_FPS,
            bitrateBps = StreamOptions.HD_BITRATE_BPS,
            codec      = StreamOptions.CODEC 
        )

        /** 640x480 @ 1.5 Mbps — low bandwidth fallback */
        fun sd() = VideoStreamConfig(
            width      = StreamOptions.SD_WIDTH,
            height     = StreamOptions.SD_HEIGHT,
            fps        = StreamOptions.SD_FPS,
            bitrateBps = StreamOptions.SD_BITRATE_BPS,
            codec      = StreamOptions.CODEC 
        )
    }
}
