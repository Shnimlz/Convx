package com.convx.music.playback

import com.convx.music.constants.AudioQuality
import com.convx.music.constants.SaavnAudioQuality

internal fun effectiveAudioQuality(selected: AudioQuality, dataSaver: Boolean): AudioQuality =
    if (dataSaver) AudioQuality.LOW else selected

internal fun effectiveSaavnQuality(selected: SaavnAudioQuality, dataSaver: Boolean): SaavnAudioQuality =
    if (dataSaver) SaavnAudioQuality.QUALITY_96 else selected

/** Streaming namespaces must not mix FLAC, normal audio and reduced-bitrate bytes. */
internal fun playbackCacheKey(mediaId: String, dataSaver: Boolean, lossless: Boolean): String = when {
    dataSaver -> "$mediaId#data-saver"
    lossless -> "$mediaId#flac"
    else -> mediaId
}
