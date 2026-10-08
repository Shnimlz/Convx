package com.convx.music.playback

enum class DownloadFormat { STANDARD, FLAC }

/** An existing request keeps its format, including legacy requests without a MIME type. */
internal fun downloadFormatForRequest(
    hasExistingRequest: Boolean,
    existingMimeType: String?,
    selected: DownloadFormat,
): DownloadFormat = if (hasExistingRequest) {
    if (existingMimeType == "audio/flac") DownloadFormat.FLAC else DownloadFormat.STANDARD
} else selected

/** Check bytes, rather than trusting a URL suffix or a provider's quality label. */
internal fun isFlacHeader(header: ByteArray): Boolean =
    header.size >= 4 && header[0] == 0x66.toByte() && header[1] == 0x4c.toByte() &&
        header[2] == 0x61.toByte() && header[3] == 0x43.toByte()
