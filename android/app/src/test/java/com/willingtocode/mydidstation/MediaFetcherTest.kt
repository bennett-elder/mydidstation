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
        assertEquals(MediaResult.Failed("not an image"), result)
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
