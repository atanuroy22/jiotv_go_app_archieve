package com.skylake.skytv.jgorunner.tata

internal object TataConstants {
    const val ZIP_URL = "https://github.com/drmlive/tataplay/archive/refs/heads/main.zip"
    const val ROOT_DIR_NAME = "tataplay"
    const val ZIP_FILE_NAME = "tataplay.zip"
    const val DEFAULT_PORT = 8000
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    const val AES_KEY = "aesEncryptionKey"
    const val ORIGIN_API = "https://tp.drmlive-01.workers.dev/origin"
    const val STB_ONLY_API = "https://tp.drmlive-01.workers.dev/stb_only"
    const val SECURE_KID_API = "https://tp.secure-kid.workers.dev/"
    const val CONTENT_API_PREFIX = "https://tb.tapi.videoready.tv/content-detail/api/partner/cdn/player/details/chotiluli/"
    const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
}
