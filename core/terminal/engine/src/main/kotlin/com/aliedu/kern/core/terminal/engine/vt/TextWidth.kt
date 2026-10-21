package com.aliedu.kern.core.terminal.engine.vt

/**
 * Display width of a Unicode code point (0 = zero-width/combining, 1 = narrow, 2 = wide).
 * A compact East-Asian-Width table — enough for a coding shell, not a full UAX #11 implementation.
 */
object TextWidth {

    fun charWidth(cp: Int): Int {
        if (cp == 0) return 0
        if (cp < 0x20 || cp == 0x7F) return 0
        if (cp in 0x0300..0x036F) return 0 // combining diacritical marks
        if (cp in 0x0483..0x0489) return 0
        if (cp in 0x0591..0x05BD || cp == 0x05BF || cp in 0x05C1..0x05C2 || cp in 0x05C4..0x05C5) return 0
        if (cp in 0x0610..0x061A || cp in 0x064B..0x065F) return 0
        if (cp in 0x0E31..0x0E3A) return 0
        if (cp in 0x1AB0..0x1AFF) return 0
        if (cp in 0x1DC0..0x1DFF) return 0
        if (cp in 0x200B..0x200F) return 0
        if (cp in 0x20D0..0x20FF) return 0
        if (cp in 0xFE00..0xFE0F) return 0 // variation selectors
        if (cp in 0xFE20..0xFE2F) return 0

        return if (isWide(cp)) 2 else 1
    }

    private fun isWide(cp: Int): Boolean = when (cp) {
        in 0x1100..0x115F -> true // Hangul Jamo
        in 0x2E80..0x303E -> true // CJK radicals + punctuation
        in 0x3041..0x33FF -> true // Hiragana/Katakana/CJK symbols
        in 0x3400..0x4DBF -> true // CJK Ext A
        in 0x4E00..0x9FFF -> true // CJK Unified
        in 0xA000..0xA4CF -> true // Yi
        in 0xAC00..0xD7A3 -> true // Hangul syllables
        in 0xF900..0xFAFF -> true // CJK compatibility ideographs
        in 0xFE30..0xFE4F -> true // CJK compatibility forms
        in 0xFF00..0xFF60 -> true // Fullwidth forms
        in 0xFFE0..0xFFE6 -> true
        in 0x1F300..0x1F64F -> true // emoji
        in 0x1F900..0x1F9FF -> true
        in 0x20000..0x3FFFD -> true // CJK Ext B+
        else -> false
    }
}
