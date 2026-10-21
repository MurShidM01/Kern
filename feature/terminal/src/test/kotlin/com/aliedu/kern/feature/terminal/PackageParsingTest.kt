package com.aliedu.kern.feature.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PackageParsingTest {

    @Test
    fun `keeps package names, sorted and de-duplicated`() {
        val names = parsePackageNames("zlib1g\ngit\ncurl\ngit\n")

        assertThat(names).containsExactly("curl", "git", "zlib1g").inOrder()
    }

    @Test
    fun `drops the tty echo of the command and the exit sentinel`() {
        // What a real `apt-cache pkgnames` run looks like on the wire: the prompt + echoed command
        // first, then the payload, then the executor's sentinel line.
        val output = """
            $ apt-cache pkgnames
            curl
            git
            __KERN_DONE_9af38d11b 0
        """.trimIndent()

        assertThat(parsePackageNames(output)).containsExactly("curl", "git").inOrder()
    }

    @Test
    fun `drops apt and dpkg warnings`() {
        val output = "W: Failed to fetch http://archive.ubuntu.com\n" +
            "E: The package lists or status file could not be parsed\n" +
            "N: Ignoring file 'x.list'\n" +
            "dpkg: warning: package 'foo' is not installed\n" +
            "curl\n"

        assertThat(parsePackageNames(output)).containsExactly("curl")
    }

    @Test
    fun `reads dpkg get-selections rows`() {
        // `dpkg --get-selections` prints "name<tab>...<tab>install"; only the name is a package.
        val output = "adduser\t\t\t\t\t\tinstall\n" +
            "apt\t\t\t\t\t\t\tinstall\n" +
            "libstdc++6:arm64\t\t\t\t\tinstall\n"

        // The architecture qualifier is dropped: the UI installs by bare name.
        assertThat(parsePackageNames(output)).containsExactly("adduser", "apt", "libstdc++6")
    }

    @Test
    fun `keeps versioned and hyphenated names`() {
        val output = "gcc-12\npython3.12\nlibstdc++6\nfd-find\n"

        assertThat(parsePackageNames(output))
            .containsExactly("fd-find", "gcc-12", "libstdc++6", "python3.12")
            .inOrder()
    }

    @Test
    fun `rejects prose that is not a package name`() {
        val output = "Reading package lists... Done\nBuilding dependency tree\ncurl\n"

        assertThat(parsePackageNames(output)).containsExactly("curl")
    }

    @Test
    fun `an empty listing yields no packages`() {
        assertThat(parsePackageNames("\n\n")).isEmpty()
    }
}
