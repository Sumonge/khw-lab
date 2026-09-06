package com.khwlab.hub.feature.camera

/** esp32-camera framesize_t 索引 → 分辨率。GC2145 支持到 UXGA(1600×1200)。 */
enum class CameraResolution(val framesize: Int, val label: String) {
    QVGA(7, "320×240"),
    VGA(9, "640×480"),
    SVGA(10, "800×600"),
    XGA(11, "1024×768"),
    UXGA(14, "1600×1200");

    companion object {
        fun fromFramesize(v: Int): CameraResolution =
            entries.firstOrNull { it.framesize == v } ?: XGA
    }
}
