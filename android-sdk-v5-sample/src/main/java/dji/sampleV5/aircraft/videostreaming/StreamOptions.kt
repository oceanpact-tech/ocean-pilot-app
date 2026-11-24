package dji.sampleV5.aircraft.videostreaming

/**
 * Configuration options for video streaming.
 */
object StreamOptions {
    // Identifiers
    const val MEDIA_STREAM_ID = "DJI_DRONE_STREAM"
    const val VIDEO_TRACK_ID  = "DJI_VIDEO_TRACK"

    // Codec & FPS
    const val CODEC        = "H264" // H264 High Profile for best quality
    const val DEFAULT_FPS  = 30
    const val SD_FPS       = 10

    // Resolutions
    const val NATIVE_WIDTH  = 0
    const val NATIVE_HEIGHT = 0

    const val FULL_HD_WIDTH  = 1920
    const val FULL_HD_HEIGHT = 1080

    const val HD_WIDTH  = 1280
    const val HD_HEIGHT = 720

    const val SD_WIDTH  = 640
    const val SD_HEIGHT = 480

    // Camera options
    const val DEFAULT_BITRATE_BPS = 4_000_000
    const val AUTO_BITRATE_BPS    = 6_000_000
    const val FULL_HD_BITRATE_BPS = 6_000_000
    const val HD_BITRATE_BPS      = 3_500_000
    const val SD_BITRATE_BPS      = 1_500_000
}