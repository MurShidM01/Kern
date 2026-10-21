package com.aliedu.kern.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliedu.kern.core.designsystem.theme.KernElevation
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.designsystem.theme.LocalKernSemanticColors

/**
 * Lightweight Markdown model. Kern renders model output as rich text rather than raw Markdown, but
 * only supports the subset LLMs actually emit: headings, paragraphs, bullet/numbered lists, fenced
 * code, blockquotes, rules, and inline emphasis/code/links.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val items: List<String>) : MdBlock
    data class Ordered(val items: List<String>) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** Line-oriented Markdown block parser (pure; unit-tested without Android). */
object MarkdownParser {

    private val headingRegex = Regex("^#{1,6}\\s+.*")
    private val bulletRegex = Regex("^[-*+]\\s+.*")
    private val orderedRegex = Regex("^\\d+[.)]\\s+.*")
    private val ruleRegex = Regex("^(\\*\\s*){3,}$|^(\\-\\s*){3,}$|^(_\\s*){3,}$")
    private val tableCellRegex = Regex("^\\s*:?-{1,}:?\\s*$")

    private fun splitTableRow(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }

    /** A table is a pipe row immediately followed by a `|---|---|` separator row. */
    private fun isTableStart(lines: List<String>, i: Int): Boolean {
        if (i + 1 >= lines.size) return false
        val header = lines[i].trim()
        val separator = lines[i + 1].trim()
        if (!header.contains('|') || !separator.contains('-')) return false
        val cells = separator.trim('|').split('|')
        return cells.isNotEmpty() && cells.all { tableCellRegex.matches(it) }
    }

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MdBlock>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
                paragraph.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    val language = trimmed.removePrefix("```").trim().ifBlank { null }
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        code.append(lines[i]).append('\n')
                        i++
                    }
                    blocks.add(MdBlock.Code(language, code.toString().trimEnd('\n')))
                }

                headingRegex.matches(trimmed) -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length
                    blocks.add(MdBlock.Heading(level, trimmed.dropWhile { it == '#' }.trim()))
                }

                ruleRegex.matches(trimmed) -> {
                    flushParagraph()
                    blocks.add(MdBlock.Rule)
                }

                trimmed.startsWith(">") -> {
                    flushParagraph()
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        quote.append(lines[i].trim().removePrefix(">").trimStart()).append(' ')
                        i++
                    }
                    blocks.add(MdBlock.Quote(quote.toString().trim()))
                    continue
                }

                bulletRegex.matches(trimmed) -> {
                    flushParagraph()
                    val items = ArrayList<String>()
                    while (i < lines.size && bulletRegex.matches(lines[i].trim())) {
                        items.add(lines[i].trim().drop(1).trimStart())
                        i++
                    }
                    blocks.add(MdBlock.Bullets(items))
                    continue
                }

                orderedRegex.matches(trimmed) -> {
                    flushParagraph()
                    val items = ArrayList<String>()
                    while (i < lines.size && orderedRegex.matches(lines[i].trim())) {
                        items.add(lines[i].trim().substringAfter(' ').trimStart())
                        i++
                    }
                    blocks.add(MdBlock.Ordered(items))
                    continue
                }

                isTableStart(lines, i) -> {
                    flushParagraph()
                    val headers = splitTableRow(lines[i])
                    i += 2 // skip the header row and the separator row
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().contains('|') && lines[i].trim().isNotEmpty()) {
                        rows.add(splitTableRow(lines[i]))
                        i++
                    }
                    blocks.add(MdBlock.Table(headers, rows))
                    continue
                }

                trimmed.isEmpty() -> flushParagraph()

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                }
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    /**
     * Renders a single line of inline Markdown (**bold**, *italic*, `code`, ~~strike~~, [links],
     * bare URLs, and @file mentions). Links and mentions are drawn in the accent colour.
     */
    fun inline(
        text: String,
        codeStyle: SpanStyle,
        linkColor: Color,
        mentionColor: Color = linkColor
    ): AnnotatedString =
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '`' -> {
                        val end = text.indexOf('`', i + 1)
                        if (end > i) {
                            withStyle(codeStyle) { append(text.substring(i + 1, end)) }
                            i = end + 1
                        } else {
                            append(c); i++
                        }
                    }

                    text.startsWith("**", i) || text.startsWith("__", i) -> {
                        val marker = text.substring(i, i + 2)
                        val end = text.indexOf(marker, i + 2)
                        if (end > i) {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                append(text.substring(i + 2, end))
                            }
                            i = end + 2
                        } else {
                            append(marker); i += 2
                        }
                    }

                    text.startsWith("~~", i) -> {
                        val end = text.indexOf("~~", i + 2)
                        if (end > i) {
                            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                                append(text.substring(i + 2, end))
                            }
                            i = end + 2
                        } else {
                            append("~~"); i += 2
                        }
                    }

                    c == '[' -> {
                        val labelEnd = text.indexOf("](", i)
                        val urlEnd = if (labelEnd > i) text.indexOf(')', labelEnd + 2) else -1
                        if (urlEnd > labelEnd) {
                            val label = text.substring(i + 1, labelEnd)
                            val url = text.substring(labelEnd + 2, urlEnd)
                            withLink(
                                LinkAnnotation.Url(
                                    url = url,
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline
                                        )
                                    )
                                )
                            ) { append(label) }
                            i = urlEnd + 1
                        } else {
                            append(c); i++
                        }
                    }

                    text.startsWith("http://", i) || text.startsWith("https://", i) -> {
                        var end = i
                        while (end < text.length && !text[end].isWhitespace()) end++
                        val url = text.substring(i, end)
                        withLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline
                                    )
                                )
                            )
                        ) { append(url) }
                        i = end
                    }

                    c == '@' -> {
                        var j = i + 1
                        while (j < text.length && (
                                text[j].isLetterOrDigit() || text[j] == '/' || text[j] == '.' ||
                                    text[j] == '-' || text[j] == '_'
                                )
                        ) {
                            j++
                        }
                        if (j > i + 1) {
                            withStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.Medium)) {
                                append(text.substring(i, j))
                            }
                            i = j
                        } else {
                            append(c); i++
                        }
                    }

                    c == '*' || c == '_' -> {
                        val end = text.indexOf(c, i + 1)
                        if (end > i + 1) {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                append(text.substring(i + 1, end))
                            }
                            i = end + 1
                        } else {
                            append(c); i++
                        }
                    }

                    else -> {
                        append(c); i++
                    }
                }
            }
        }
}

/** Renders Markdown model output with the Kern type/colour tokens. */
@Composable
fun KernMarkdown(text: String, modifier: Modifier = Modifier, fontSizeSp: Float? = null) {
    val semantic = LocalKernSemanticColors.current
    val typography = MaterialTheme.typography
    // When a chat font size is supplied, scale the body/small styles around it so the whole
    // transcript (paragraphs, lists, quotes, tables) tracks the user's preference.
    val bodyStyle = if (fontSizeSp == null) typography.bodyMedium else typography.bodyMedium.copy(
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * 1.5f).sp
    )
    val smallStyle = if (fontSizeSp == null) typography.bodySmall else typography.bodySmall.copy(
        fontSize = (fontSizeSp * 0.9f).sp,
        lineHeight = (fontSizeSp * 1.35f).sp
    )
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = smallStyle.fontSize,
        background = semantic.codeBackground,
        color = semantic.codeText
    )
    val linkColor = MaterialTheme.colorScheme.tertiary
    val mentionColor = MaterialTheme.colorScheme.tertiary
    val blocks = remember(text) { MarkdownParser.parse(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = MarkdownParser.inline(block.text, codeStyle, linkColor, mentionColor),
                    style = when (block.level) {
                        1 -> typography.headlineSmall
                        2 -> typography.titleMedium
                        3 -> typography.titleSmall
                        else -> typography.bodyLarge
                    }.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                is MdBlock.Paragraph -> Text(
                    text = MarkdownParser.inline(block.text, codeStyle, linkColor, mentionColor),
                    style = bodyStyle,
                    color = MaterialTheme.colorScheme.onSurface
                )

                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.xxs)) {
                    block.items.forEach { item ->
                        Row {
                            Text(
                                "•",
                                style = bodyStyle,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(KernSpacing.md)
                            )
                            Text(
                                text = MarkdownParser.inline(item, codeStyle, linkColor, mentionColor),
                                style = bodyStyle,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                is MdBlock.Ordered -> Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.xxs)) {
                    block.items.forEachIndexed { index, item ->
                        Row {
                            Text(
                                "${index + 1}.",
                                style = bodyStyle,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(KernSpacing.lg)
                            )
                            Text(
                                text = MarkdownParser.inline(item, codeStyle, linkColor, mentionColor),
                                style = bodyStyle,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                is MdBlock.Code -> CodeBlock(block.language, block.code, semantic.codeBackground, semantic.codeText)

                is MdBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    Box(
                        modifier = Modifier
                            .width(KernSpacing.xxs)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, KernRadius.xsShape)
                    )
                    Spacer(Modifier.width(KernSpacing.sm))
                    Text(
                        text = MarkdownParser.inline(block.text, codeStyle, linkColor, mentionColor),
                        style = smallStyle.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is MdBlock.Table -> MarkdownTable(block, codeStyle, linkColor, mentionColor)

                MdBlock.Rule -> KernDivider(modifier = Modifier.padding(vertical = KernSpacing.xs))
            }
        }
    }
}

@Composable
private fun MarkdownTable(
    table: MdBlock.Table,
    codeStyle: SpanStyle,
    linkColor: Color,
    mentionColor: Color
) {
    val columns = table.headers.size.coerceAtLeast(1)
    KernSurface(modifier = Modifier.fillMaxWidth(), shape = KernRadius.smShape) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                table.headers.forEachIndexed { index, cell ->
                    Text(
                        text = MarkdownParser.inline(cell, codeStyle, linkColor, mentionColor),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xs)
                    )
                    if (index < columns - 1) {
                        Box(
                            modifier = Modifier
                                .width(KernElevation.hairline)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
            }
            KernDivider()
            table.rows.forEachIndexed { rowIndex, row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    repeat(columns) { index ->
                        val cell = row.getOrElse(index) { "" }
                        Text(
                            text = MarkdownParser.inline(cell, codeStyle, linkColor, mentionColor),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xs)
                        )
                        if (index < columns - 1) {
                            Box(
                                modifier = Modifier
                                    .width(KernElevation.hairline)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                        }
                    }
                }
                if (rowIndex < table.rows.lastIndex) KernDivider()
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String?, code: String, background: Color, textColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, KernRadius.smShape)
            .padding(KernSpacing.sm)
    ) {
        if (language != null) {
            Text(
                text = language.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = KernSpacing.xxs)
            )
        }
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp
            ),
            color = textColor,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        )
    }
}
