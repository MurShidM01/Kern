package com.aliedu.kern.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The update prompt must not appear for a build the user already has, and must appear for anything
 * newer — including the tag formats GitHub actually publishes ("v1.0.2", "1.0.2+261029").
 */
class UpdateVersionTest {

    @Test
    fun newerPatchVersionIsAnUpdate() {
        assertThat(isNewerVersion("1.0.2", "1.0.0")).isTrue()
    }

    @Test
    fun runningVersionIsNotAnUpdate() {
        assertThat(isNewerVersion("1.0.2", "1.0.2")).isFalse()
    }

    @Test
    fun olderReleaseIsNeverOffered() {
        assertThat(isNewerVersion("1.0.0", "1.0.2")).isFalse()
    }

    @Test
    fun tagPrefixAndBuildSuffixAreIgnored() {
        assertThat(isNewerVersion("v1.0.2+261029", "1.0.2")).isFalse()
        assertThat(isNewerVersion("v1.1.0+281101", "1.0.2+261029")).isTrue()
    }

    @Test
    fun missingComponentCountsAsZero() {
        assertThat(isNewerVersion("1.1", "1.0.9")).isTrue()
        assertThat(isNewerVersion("1.0", "1.0.1")).isFalse()
    }
}
