package com.convx.music.playback

/** HTTP range ends are inclusive. Unknown lengths must remain open-ended. */
internal fun downloadByteRange(position: Long, length: Long, contentLength: Long?): String {
    val end = when {
        length > 0 -> (position + length - 1).toString()
        contentLength != null && contentLength > position -> (contentLength - 1).toString()
        else -> ""
    }
    return "$position-$end"
}
