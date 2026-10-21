package com.aliedu.kern.core.terminal.implementation.install

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChecksumVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `sha256 matches a known vector`() {
        val file = tmp.newFile("abc.txt")
        file.writeText("abc")
        assertThat(ChecksumVerifier().sha256(file))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `verify is case insensitive and rejects a mismatch`() {
        val file = tmp.newFile("x.bin")
        file.writeText("hello")
        val verifier = ChecksumVerifier()
        val digest = verifier.sha256(file)
        assertThat(verifier.verify(file, digest.uppercase())).isTrue()
        assertThat(verifier.verify(file, "00".repeat(32))).isFalse()
    }

    @Test
    fun `missing file fails verification`() {
        assertThat(ChecksumVerifier().verify(java.io.File("does-not-exist"), "abc")).isFalse()
    }
}

class DiskSpaceCheckerTest {

    @Test
    fun `has room accounts for the safety margin`() {
        val checker = DiskSpaceChecker { 1_000 }
        assertThat(checker.hasRoomFor(600, 300)).isTrue()
        assertThat(checker.hasRoomFor(800, 300)).isFalse()
    }
}

class DistroCatalogTest {

    @Test
    fun `decodes the shipped catalog shape`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val catalog = json.decodeFromString(
            DistroCatalog.serializer(),
            """
            {
              "schemaVersion": 2, "defaultDistroId": "alpine",
              "distros": [
                {
                  "id": "alpine", "name": "Alpine Linux", "version": "3.22.2",
                  "summary": "Smallest", "shell": "/bin/sh", "rootfsDir": "rootfs",
                  "minFreeBytesAfterInstall": 268435456,
                  "abis": {
                    "arm64-v8a": {
                      "installedSizeBytes": 83886080,
                      "components": [
                        { "id": "rootfs", "url": "https://example/alpine.tar.gz",
                          "sha256": "abc", "sizeBytes": 3949657, "archive": "tar.gz", "target": "rootfs" }
                      ],
                      "expectedFiles": ["rootfs/bin/sh"]
                    }
                  }
                },
                {
                  "id": "ubuntu", "name": "Ubuntu", "version": "24.04.1 LTS",
                  "shell": "/bin/bash", "rootfsDir": "rootfs",
                  "abis": {
                    "arm64-v8a": {
                      "installedSizeBytes": 353160000,
                      "components": [
                        { "id": "rootfs", "url": "https://example/ubuntu.tar.xz",
                          "sha256": "def", "sizeBytes": 65051348, "archive": "tar.xz", "target": "rootfs" }
                      ],
                      "expectedFiles": ["rootfs/bin/sh"]
                    }
                  }
                }
              ]
            }
            """.trimIndent()
        )
        assertThat(catalog.defaultDistroId).isEqualTo("alpine")
        assertThat(catalog.distros.map { it.id }).containsExactly("alpine", "ubuntu")
        val ubuntu = catalog.find("ubuntu")!!
        assertThat(ubuntu.shell).isEqualTo("/bin/bash")
        val bundle = ubuntu.abis.getValue("arm64-v8a")
        assertThat(bundle.components).hasSize(1)
        assertThat(bundle.components.first().archive).isEqualTo("tar.xz")
        assertThat(bundle.downloadBytes).isEqualTo(65051348L)
        assertThat(bundle.expectedFiles).containsExactly("rootfs/bin/sh")
    }

    @Test
    fun `downloadBytes is unknown when a component has no size`() {
        val bundle = AbiBundle(
            installedSizeBytes = 1,
            components = listOf(BundleComponent(id = "rootfs", url = "u", sha256 = "s", target = "rootfs"))
        )
        assertThat(bundle.downloadBytes).isNull()
    }
}
