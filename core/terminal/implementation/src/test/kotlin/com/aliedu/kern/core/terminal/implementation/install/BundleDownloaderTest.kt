package com.aliedu.kern.core.terminal.implementation.install

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BundleDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: BundleDownloader
    private val verifier = ChecksumVerifier()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = BundleDownloader(OkHttpClient(), verifier)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun component(sha: String, size: Long?) = BundleComponent(
        id = "minirootfs",
        url = server.url("/bundle.bin").toString(),
        sha256 = sha,
        sizeBytes = size,
        archive = "none",
        target = "rootfs"
    )

    private fun body(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun shaOf(bytes: ByteArray): String {
        val file = tmp.newFile("sha-${bytes.size}-${System.nanoTime()}.bin")
        file.writeBytes(bytes)
        return verifier.sha256(file)
    }

    @Test
    fun `downloads and verifies a component`() = runBlocking {
        val bytes = body(50_000)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))

        val target = File(tmp.root, "out.bin")
        val result = downloader.download(component(shaOf(bytes), bytes.size.toLong()), target) { _, _ -> }

        assertThat(result.isSuccess).isTrue()
        assertThat(target.readBytes()).isEqualTo(bytes)
        assertThat(File(tmp.root, "out.bin.part").exists()).isFalse()
    }

    @Test
    fun `resumes a partial download using Range`() = runBlocking {
        val bytes = body(40_000)
        val partialLength = 15_000
        val target = File(tmp.root, "resume.bin")
        File(tmp.root, "resume.bin.part").writeBytes(bytes.copyOfRange(0, partialLength))

        val ranges = ArrayList<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                ranges.add(request.getHeader("Range"))
                val range = request.getHeader("Range")
                return if (range == "bytes=$partialLength-") {
                    MockResponse().setResponseCode(206)
                        .setBody(Buffer().write(bytes, partialLength, bytes.size - partialLength))
                } else {
                    MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))
                }
            }
        }

        val result = downloader.download(component(shaOf(bytes), bytes.size.toLong()), target) { _, _ -> }

        assertThat(result.isSuccess).isTrue()
        assertThat(target.readBytes()).isEqualTo(bytes)
        assertThat(ranges.first()).isEqualTo("bytes=$partialLength-")
    }

    @Test
    fun `retries after a server error`() = runBlocking {
        val bytes = body(10_000)
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))

        val target = File(tmp.root, "retry.bin")
        val result = downloader.download(component(shaOf(bytes), bytes.size.toLong()), target) { _, _ -> }

        assertThat(result.isSuccess).isTrue()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `fails on a checksum mismatch and removes the partial file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().writeUtf8("corrupted")))

        val target = File(tmp.root, "bad.bin")
        val result = downloader.download(component("00".repeat(32), null), target) { _, _ -> }

        assertThat(result.isFailure).isTrue()
        assertThat(target.exists()).isFalse()
        assertThat(File(tmp.root, "bad.bin.part").exists()).isFalse()
    }
}
