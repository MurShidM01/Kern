package com.aliedu.kern.feature.preview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** How a console line is attributed to a file, the way a browser console does. */
class ConsoleOriginTest {

    @Test
    fun `a named script keeps its file name`() {
        assertThat(consoleOrigin("http://127.0.0.1:8000/index.html", 10)).isEqualTo("index.html:10")
        assertThat(consoleOrigin("http://127.0.0.1:8000/js/app.js", 3)).isEqualTo("app.js:3")
    }

    @Test
    fun `an inline script on the origin shows only the line`() {
        assertThat(consoleOrigin("http://127.0.0.1:8000/", 10)).isEqualTo("10")
        assertThat(consoleOrigin("http://127.0.0.1:8000", 10)).isEqualTo("10")
    }

    @Test
    fun `a line-less entry shows only the file`() {
        assertThat(consoleOrigin("http://127.0.0.1:8000/app.js", 0)).isEqualTo("app.js")
    }

    @Test
    fun `an entry with no source at all shows nothing`() {
        assertThat(consoleOrigin(null, 0)).isEmpty()
        assertThat(consoleOrigin("", 0)).isEmpty()
    }
}
