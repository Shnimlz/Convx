package com.convx.music.utils.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** Self-check for the base64 "bts" manifest decode — no live server needed. */
class TidalServiceTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun bts_manifest_yields_direct_flac_url() {
        val url = "https://lgf.audio.tidal.com/mediatracks/abc/0.flac"
        val manifest = b64("""{"mimeType":"audio/flac","urls":["$url"]}""")
        assertEquals(url, TidalService.decodeManifestUrl(manifest, "application/vnd.tidal.bts"))
    }

    @Test
    fun dash_mimeType_is_not_decoded() {
        val manifest = b64("""{"mimeType":"audio/flac","urls":["https://x/y.flac"]}""")
        assertNull(TidalService.decodeManifestUrl(manifest, "application/dash+xml"))
    }

    @Test
    fun lossy_manifest_is_not_mislabeled_as_flac() {
        val manifest = b64("""{"mimeType":"audio/mp4","urls":["https://x/fake.flac"]}""")
        assertNull(TidalService.decodeManifestUrl(manifest, "application/vnd.tidal.bts"))
    }

    @Test
    fun encrypted_flac_is_not_a_downloadable_native_file() {
        val manifest = b64("""{"mimeType":"audio/flac","encryptionType":"OLD_AES","urls":["https://x/a.flac"]}""")
        assertNull(TidalService.decodeManifestUrl(manifest, "application/vnd.tidal.bts"))
    }

    @Test
    fun missing_mime_and_non_http_urls_are_rejected() {
        assertNull(TidalService.decodeManifestUrl(b64("""{"urls":["https://x/a.flac"]}"""), "application/vnd.tidal.bts"))
        assertNull(TidalService.decodeManifestUrl(b64("""{"mimeType":"audio/flac","urls":["file:///a.flac"]}"""), "application/vnd.tidal.bts"))
    }

    @Test
    fun unencrypted_flac_alias_is_supported() {
        val manifest = b64("""{"mimeType":"audio/x-flac","encryptionType":"NONE","urls":["https://x/a"]}""")
        assertEquals("https://x/a", TidalService.decodeManifestUrl(manifest, "application/vnd.tidal.bts"))
    }

    @Test
    fun mpd_xml_payload_returns_null() {
        val manifest = b64("""<?xml version="1.0"?><MPD></MPD>""")
        assertNull(TidalService.decodeManifestUrl(manifest, "application/vnd.tidal.bts"))
    }

    @Test
    fun empty_or_garbage_returns_null() {
        assertNull(TidalService.decodeManifestUrl(null, "application/vnd.tidal.bts"))
        assertNull(TidalService.decodeManifestUrl("", "application/vnd.tidal.bts"))
        assertNull(TidalService.decodeManifestUrl("!!!not-base64!!!", "application/vnd.tidal.bts"))
    }
}
