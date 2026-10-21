package com.aliedu.kern.feature.preview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rules that keep the preview pointing at the dev server: what it may show itself, and what it
 * hands to the system browser instead.
 */
class PreviewUrlRulesTest {

    @Test
    fun `loopback hosts are recognised in every form a dev server prints`() {
        assertThat(WebViewPreviewHost.isLoopback("localhost")).isTrue()
        assertThat(WebViewPreviewHost.isLoopback("127.0.0.1")).isTrue()
        assertThat(WebViewPreviewHost.isLoopback("::1")).isTrue()
        assertThat(WebViewPreviewHost.isLoopback("[::1]")).isTrue()
        assertThat(WebViewPreviewHost.isLoopback("0.0.0.0")).isTrue()
        assertThat(WebViewPreviewHost.isLoopback("10.0.2.2")).isTrue()
        assertThat(WebViewPreviewHost.isLoopback("LOCALHOST")).isTrue()
    }

    @Test
    fun `a remote host is not loopback`() {
        assertThat(WebViewPreviewHost.isLoopback("example.com")).isFalse()
        assertThat(WebViewPreviewHost.isLoopback("192.168.1.10")).isFalse()
        assertThat(WebViewPreviewHost.isLoopback("127.0.0.1.evil.com")).isFalse()
        assertThat(WebViewPreviewHost.isLoopback("")).isFalse()
    }

    @Test
    fun `the local server and inline schemes stay in the preview`() {
        assertThat(WebViewPreviewHost.isPreviewableUrl("http", "127.0.0.1")).isTrue()
        assertThat(WebViewPreviewHost.isPreviewableUrl("https", "localhost")).isTrue()
        assertThat(WebViewPreviewHost.isPreviewableUrl("HTTP", "10.0.2.2")).isTrue()
        assertThat(WebViewPreviewHost.isPreviewableUrl("about", null)).isTrue()
        assertThat(WebViewPreviewHost.isPreviewableUrl("data", null)).isTrue()
        assertThat(WebViewPreviewHost.isPreviewableUrl("blob", null)).isTrue()
    }

    @Test
    fun `anything else is handed to the system browser`() {
        assertThat(WebViewPreviewHost.isPreviewableUrl("http", "example.com")).isFalse()
        assertThat(WebViewPreviewHost.isPreviewableUrl("https", "github.com")).isFalse()
        assertThat(WebViewPreviewHost.isPreviewableUrl("file", "")).isFalse()
        assertThat(WebViewPreviewHost.isPreviewableUrl("intent", null)).isFalse()
        assertThat(WebViewPreviewHost.isPreviewableUrl(null, "127.0.0.1")).isFalse()
    }

    @Test
    fun `evaluate results are unquoted`() {
        assertThat(WebViewPreviewHost.unquote("\"hello\"")).isEqualTo("hello")
        assertThat(WebViewPreviewHost.unquote("42")).isEqualTo("42")
        assertThat(WebViewPreviewHost.unquote("true")).isEqualTo("true")
        assertThat(WebViewPreviewHost.unquote("{\"a\":1}")).isEqualTo("{\"a\":1}")
        assertThat(WebViewPreviewHost.unquote("[1,2]")).isEqualTo("[1,2]")
    }

    @Test
    fun `a missing or unparsable result is null rather than a crash`() {
        assertThat(WebViewPreviewHost.unquote(null)).isNull()
        assertThat(WebViewPreviewHost.unquote("null")).isNull()
        assertThat(WebViewPreviewHost.unquote("not json")).isEqualTo("not json")
    }
}
