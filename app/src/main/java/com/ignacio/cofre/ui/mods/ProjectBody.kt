package com.ignacio.cofre.ui.mods

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.RemoteImage
import com.ignacio.cofre.design.TextRole
import com.ignacio.cofre.design.outline
import com.ignacio.cofre.kit.pages.ProjectPage

/**
 * A project body or changelog, drawn natively: Markdown (Modrinth, with the inline HTML its pages mix in) as
 * headings, paragraphs, lists, quotes, code, tables and images; HTML (CurseForge) as styled text.
 */
@Composable
internal fun ProjectBody(
    body: ProjectPage.Body,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(body) {
        when (body) {
            is ProjectPage.Body.Markdown -> Markdown.parse(body.text)
            is ProjectPage.Body.Html -> listOf(MdBlock.Html(body.text))
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { MdBlockView(it) }
    }
}

@Composable
private fun MdBlockView(block: MdBlock) {
    val c = CofreTheme.colors
    val type = CofreTheme.type
    when (block) {
        is MdBlock.Heading -> CofreText(
            block.text,
            when (block.level) {
                1 -> type.title2
                2 -> type.header
                else -> type.rowTitle
            },
            Modifier.padding(top = 6.dp),
        )
        is MdBlock.Paragraph -> Inline(block.text, type.body)
        is MdBlock.ListItems -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { i, item ->
                Row(Modifier.padding(start = (item.depth * 14).dp)) {
                    CofreText(if (block.ordered) "${i + 1}." else "•", type.body, Modifier.width(22.dp), color = c.accent)
                    Inline(item.text, type.body, Modifier.weight(1f))
                }
            }
        }
        is MdBlock.Quote -> Row {
            Box(Modifier.width(3.dp).height(20.dp).background(c.accent))
            Inline(block.text, type.secondary, Modifier.padding(start = 10.dp), color = c.inkSoft)
        }
        is MdBlock.Code -> Box(
            Modifier.fillMaxWidth().background(c.well).outline().horizontalScroll(rememberScrollState()).padding(10.dp),
        ) { CofreText(block.text, type.code, color = c.inkSoft) }
        is MdBlock.Image -> RemoteImage(
            block.url,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(c.well),
            ContentScale.Fit,
        )
        is MdBlock.Table -> Column(
            Modifier.fillMaxWidth().background(c.well).outline().horizontalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val widths = block.rows.fold(IntArray(0)) { acc, row ->
                IntArray(maxOf(acc.size, row.size)) { i -> maxOf(acc.getOrElse(i) { 0 }, row.getOrNull(i)?.length ?: 0) }
            }
            block.rows.forEachIndexed { r, row ->
                val line = row.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ")
                CofreText(line, type.code, color = if (r == 0) c.ink else c.inkSoft)
            }
        }
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(2.dp).background(c.bevel))
        is MdBlock.Html -> {
            val accent = c.accent
            val text = remember(block.text, accent) {
                AnnotatedString.fromHtml(block.text, TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)))
            }
            BasicText(text, style = type.body.style.copy(color = c.ink))
        }
    }
}

@Composable
private fun Inline(
    text: String,
    role: TextRole,
    modifier: Modifier = Modifier,
    color: Color = CofreTheme.colors.ink,
) {
    val c = CofreTheme.colors
    val code = CofreTheme.type.code.style
    val styled = remember(text, c) { Markdown.inline(text, c.accent, c.well, code.fontFamily) }
    BasicText(styled, modifier, style = role.style.copy(color = color))
}

@Immutable
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItems(val ordered: Boolean, val items: List<Item>) : MdBlock {
        data class Item(val depth: Int, val text: String)
    }
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Image(val url: String, val alt: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
    data class Html(val text: String) : MdBlock
}

/** A small CommonMark subset, enough for mod pages. Unknown HTML is dropped, never run. */
internal object Markdown {
    private val image = Regex("""^!\[([^\]]*)]\(([^)\s]+)[^)]*\)$""")
    private val bullet = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")
    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val tableDivider = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(source: String): List<MdBlock> {
        val lines = cleanHtml(source).replace("\r\n", "\n").lines()
        val out = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            val text = paragraph.toString().trim()
            paragraph.clear()
            if (text.isEmpty()) return
            val img = image.find(text)
            out += if (img != null) MdBlock.Image(img.groupValues[2], img.groupValues[1]) else MdBlock.Paragraph(text)
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    flush()
                    val fence = trimmed.take(3)
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) code += lines[i++]
                    out += MdBlock.Code(code.joinToString("\n"))
                }
                trimmed.isEmpty() -> flush()
                heading.matches(trimmed) -> {
                    flush()
                    val m = heading.find(trimmed)!!
                    out += MdBlock.Heading(m.groupValues[1].length, strip(m.groupValues[2]))
                }
                rule.matches(trimmed) -> { flush(); out += MdBlock.Rule }
                trimmed.startsWith(">") -> {
                    flush()
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) quote += lines[i++].trim().removePrefix(">").trim()
                    i--
                    out += MdBlock.Quote(quote.joinToString(" "))
                }
                trimmed.startsWith("|") && i + 1 < lines.size && tableDivider.matches(lines[i + 1]) -> {
                    flush()
                    val rows = mutableListOf(cells(trimmed))
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) rows += cells(lines[i++].trim())
                    i--
                    out += MdBlock.Table(rows.map { row -> row.map(::strip) })
                }
                bullet.matches(line) -> {
                    flush()
                    val ordered = bullet.find(line)!!.groupValues[2].first().isDigit()
                    val items = mutableListOf<MdBlock.ListItems.Item>()
                    while (i < lines.size) {
                        val m = bullet.find(lines[i]) ?: break
                        items += MdBlock.ListItems.Item(m.groupValues[1].length / 2, m.groupValues[3])
                        i++
                    }
                    i--
                    out += MdBlock.ListItems(ordered, items)
                }
                else -> {
                    // Images on their own line become blocks even inside a paragraph run.
                    if (image.matches(trimmed)) {
                        flush()
                        paragraph.append(trimmed)
                        flush()
                    } else {
                        paragraph.append(if (paragraph.isEmpty()) trimmed else " $trimmed")
                    }
                }
            }
            i++
        }
        flush()
        return out
    }

    /** `**bold**`, `*italic*`, `` `code` ``, `[link](url)`; inline images keep their alt text. */
    fun inline(text: String, accent: Color, well: Color, mono: androidx.compose.ui.text.font.FontFamily?): AnnotatedString = buildAnnotatedString {
        val token = Regex("""(\*\*|__)(.+?)\1|(?<![\w*])([*_])(?!\s)(.+?)(?<!\s)\3(?![\w*])|`([^`]+)`|!\[([^\]]*)]\([^)]*\)|\[([^\]]+)]\(([^)\s]+)[^)]*\)""")
        var at = 0
        for (m in token.findAll(text)) {
            append(text.substring(at, m.range.first))
            val g = m.groups
            when {
                g[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(g[2]!!.value, accent, well, mono)) }
                g[4] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(g[4]!!.value, accent, well, mono)) }
                g[5] != null -> withStyle(SpanStyle(fontFamily = mono, background = well)) { append(g[5]!!.value) }
                g[6] != null -> append(g[6]!!.value)
                g[7] != null -> withLink(LinkAnnotation.Url(g[8]!!.value, TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)))) {
                    append(inline(g[7]!!.value, accent, well, mono))
                }
            }
            at = m.range.last + 1
        }
        append(text.substring(at))
    }

    /** Inline HTML → Markdown: images and links survive, line breaks and blocks split, everything else goes. */
    internal fun cleanHtml(text: String): String = text
        .replace(Regex("""<(script|style|iframe)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("""<img\b[^>]*?src\s*=\s*["']([^"']+)["'][^>]*?(?:alt\s*=\s*["']([^"']*)["'])?[^>]*>""", RegexOption.IGNORE_CASE)) { "\n\n![${it.groupValues[2]}](${it.groupValues[1]})\n\n" }
        .replace(Regex("""<a\b[^>]*?href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            val inner = m.groupValues[2].trim()
            if (inner.startsWith("![") || inner.isEmpty()) inner else "[$inner](${m.groupValues[1]})"
        }
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""</?(p|div|center|details|summary|h\d)\b[^>]*>""", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("""<(b|strong)>(.*?)</\1>""", RegexOption.IGNORE_CASE)) { "**${it.groupValues[2]}**" }
        .replace(Regex("""<[^>]+>"""), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")

    private fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    /** Drops inline marks for places that show plain text (headings, table cells). */
    private fun strip(text: String): String = text.replace(Regex("""[*_`]"""), "").replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
}
