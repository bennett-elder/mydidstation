package com.willingtocode.mydidstation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The attachment fetcher, which is where untrusted bytes are handled.
 *
 * Every case here is something a sender can arrange: a real image, a file that lies about its
 * type, an expired link, and a body far larger than an MMS should ever be. No Robolectric:
 * this class touches no Android API, only files and HTTP.
 */
class MediaFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File

    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        root = Files.createTempDirectory("mms-test").toFile()
    }

    @After
    fun stop() {
        server.shutdown()
        root.deleteRecursively()
    }

    private fun fetcher() = MediaFetcher(OkHttpClient(), root)

    private fun url(path: String) = server.url(path).toString()

    @Test
    fun `a png is stored and identified from its bytes`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(pngBytes))
        )
        val result = fetcher().fetch(url("/media/one"))
        assertTrue("expected a load, got $result", result is MediaResult.Loaded)
        val loaded = result as MediaResult.Loaded
        assertEquals(MediaType.Png, loaded.type)
        assertTrue("the file should be on disk", loaded.file.isFile)
        assertTrue("the directory is not where expected", loaded.file.absolutePath.contains("mms"))
    }

    @Test
    fun `a file that lies about being an image is refused`() = runBlocking {
        // The header says image/jpeg and the URL says .png. The bytes say text.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/png")
                .setBody("this is not an image at all")
        )
        val result = fetcher().fetch(url("/media/liar.png"))
        assertEquals(MediaResult.Failed("unrecognized attachment"), result)
        assertFalse("nothing should have been written", File(root, "mms").listFiles().orEmpty().any())
    }

    @Test
    fun `an expired link says so rather than looking broken`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            MediaResult.Failed("attachment expired"),
            fetcher().fetch(url("/media/gone.png")),
        )
    }

    @Test
    fun `a body over the cap is refused mid-stream`() = runBlocking {
        val oversize = Buffer()
        // A real PNG header, so the refusal is about size and not about type.
        oversize.write(pngBytes)
        repeat(9) { oversize.write(ByteArray(1024 * 1024)) }
        server.enqueue(MockResponse().setBody(oversize))

        val result = fetcher().fetch(url("/media/huge.png"))
        assertTrue("expected a refusal, got $result", result is MediaResult.Failed)
        assertTrue(
            "the reason should mention size: $result",
            (result as MediaResult.Failed).reason.contains("too large"),
        )
        assertFalse(
            "nothing should have been left behind",
            File(root, "mms").listFiles().orEmpty().any { it.isFile },
        )
    }

    @Test
    fun `two bubbles asking for the same attachment download it once`() = runBlocking {
        // Two messages can share a URL, and both bubbles compose at the same time. Without
        // the per-URL lock they race on one temp file and one of them fails to store it.
        server.enqueue(MockResponse().setBody(Buffer().write(pngBytes)))
        val fetcher = fetcher()
        val results = coroutineScope {
            (1..4).map { async { fetcher.fetch(url("/media/shared")) } }.awaitAll()
        }
        assertTrue(
            "every caller should get the file, got $results",
            results.all { it is MediaResult.Loaded },
        )
        assertEquals("only one download should have happened", 1, server.requestCount)
    }

    /** A minimal ISO-BMFF file: ftyp with [brand], then moov with one track of [handler]. */
    private fun container(brand: String, handler: String): ByteArray {
        fun box(type: String, payload: ByteArray): ByteArray {
            val size = 8 + payload.size
            return byteArrayOf(
                (size ushr 24).toByte(), (size ushr 16).toByte(),
                (size ushr 8).toByte(), size.toByte(),
            ) + type.toByteArray(Charsets.US_ASCII) + payload
        }
        val ftyp = box("ftyp", brand.toByteArray(Charsets.US_ASCII) + ByteArray(8))
        // hdlr payload: version and flags, a predefined field, then the handler type.
        val hdlr = box(
            "hdlr",
            ByteArray(8) + handler.toByteArray(Charsets.US_ASCII) + ByteArray(13),
        )
        val moov = box("moov", box("trak", box("mdia", hdlr)))
        val mdat = box("mdat", ByteArray(16))
        return ftyp + moov + mdat
    }

    private fun mp4(handler: String) = container("isom", handler)

    private fun threegp(handler: String) = container("3gp4", handler)

    @Test
    fun `an mp4 with only an audio track becomes a voice note`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(mp4("soun"))))
        val result = fetcher().fetch(url("/media/voice.m4a"))
        assertTrue("expected a load, got $result", result is MediaResult.Loaded)
        assertEquals(MediaType.M4a, (result as MediaResult.Loaded).type)
        assertEquals(MediaKind.Audio, result.type.kind)
    }

    @Test
    fun `an mp4 with a video track stays a video`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(mp4("vide"))))
        val result = fetcher().fetch(url("/media/clip.mp4"))
        assertTrue("expected a load, got $result", result is MediaResult.Loaded)
        assertEquals(MediaType.Mp4, (result as MediaResult.Loaded).type)
        assertEquals(MediaKind.Video, result.type.kind)
    }

    @Test
    fun `a plus in the token is escaped before the request goes out`() = runBlocking {
        // These tokens are base64, so a '+' is data. Sent raw it arrives as a space, and the
        // server answers 200 with an empty body — the failure that reads as "empty
        // attachment" rather than as a bad token.
        server.enqueue(MockResponse().setBody(Buffer().write(pngBytes)))
        val result = fetcher().fetch(url("/media.php?map=ab+cd/ef=="))
        assertTrue("expected a load, got $result", result is MediaResult.Loaded)
        val path = server.takeRequest().path.orEmpty()
        assertTrue("the '+' should have been escaped, got $path", path.contains("%2B"))
        assertFalse("no bare '+' should be on the wire, got $path", path.contains("+"))
    }

    @Test
    fun `an audio-only 3gp becomes inline audio`() = runBlocking {
        // The container a real MMS voice note arrives in, from a carrier rather than a chat
        // app. Its extension is shared with 3gp video, so only the tracks can say which.
        server.enqueue(MockResponse().setBody(Buffer().write(threegp("soun"))))
        val result = fetcher().fetch(url("/media/voice.3gp"))
        assertTrue("expected a load, got $result", result is MediaResult.Loaded)
        assertEquals(MediaType.ThreeGpAudio, (result as MediaResult.Loaded).type)
        assertEquals(MediaKind.Audio, result.type.kind)
        assertTrue("the name should still be 3gp", result.file.name.endsWith(".3gp"))
    }

    @Test
    fun `a 3gp with a video track stays a video`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(threegp("vide"))))
        val result = fetcher().fetch(url("/media/clip.3gp"))
        assertEquals(MediaType.ThreeGp, (result as MediaResult.Loaded).type)
        assertEquals(MediaKind.Video, result.type.kind)
    }

    @Test
    fun `a cached 3gp is resolved from its tracks, not its extension`() = runBlocking {
        // .3gp is used by audio and video alike, so a cache hit that trusted the name would
        // turn a voice note into a video the second time it was opened.
        server.enqueue(MockResponse().setBody(Buffer().write(threegp("soun"))))
        val fetcher = fetcher()
        val first = fetcher.fetch(url("/media/voice.3gp"))
        val second = fetcher.fetch(url("/media/voice.3gp"))
        assertEquals(MediaType.ThreeGpAudio, (second as MediaResult.Loaded).type)
        assertEquals(first, second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a second fetch comes off the cache`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(pngBytes)))
        val fetcher = fetcher()
        val first = fetcher.fetch(url("/media/one"))
        val second = fetcher.fetch(url("/media/one"))
        assertEquals(first, second)
        assertEquals("the second fetch should not have asked the network", 1, server.requestCount)
    }
}
