package com.aliedu.kern.core.terminal.implementation.runtime

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Incremental UTF-8 decoder. A multibyte character may be split across two `read()` calls, so we
 * keep both decoder state and any unconsumed trailing bytes between chunks. `CharsetDecoder` leaves
 * an incomplete trailing sequence in the input buffer (it does not buffer it internally), so the
 * caller must carry it forward — otherwise a split character would decode as replacement chars.
 */
internal class StreamingUtf8 {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private var carry = ByteArray(0)

    fun decode(bytes: ByteArray, length: Int): String {
        if (length <= 0) return ""
        val input = if (carry.isEmpty()) bytes.copyOfRange(0, length) else carry + bytes.copyOfRange(0, length)

        val inBuf = ByteBuffer.wrap(input)
        val out = CharBuffer.allocate(input.size + 1)
        decoder.decode(inBuf, out, false)

        if (inBuf.hasRemaining()) {
            if (inBuf.remaining() <= MAX_SEQUENCE) {
                // A valid-but-incomplete trailing sequence: carry it into the next chunk.
                carry = ByteArray(inBuf.remaining()).also { inBuf.get(it) }
            } else {
                // Runaway garbage — force a flush so we do not accumulate unbounded state.
                decoder.decode(inBuf, out, true)
                decoder.flush(out)
                decoder.reset()
                carry = ByteArray(0)
            }
        } else {
            carry = ByteArray(0)
        }

        out.flip()
        return out.toString()
    }

    fun reset() {
        decoder.reset()
        carry = ByteArray(0)
    }

    private companion object {
        const val MAX_SEQUENCE = 4
    }
}
