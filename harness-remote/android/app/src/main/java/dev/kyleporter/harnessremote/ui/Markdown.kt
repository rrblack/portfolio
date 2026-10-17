package dev.kyleporter.harnessremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val marker: String, val indent: Int, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Code(val text: String) : Block
    data object Rule : Block
}

private val bulletRe = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val headingRe = Regex("^(#{1,6})\\s+(.*)$")

private fun parseBlocks(md: String): List<Block> {
    val out = ArrayList<Block>()
    val para = StringBuilder()
    fun endPara() {
        if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim())
        para.setLength(0)
    }
    val lines = md.replace("\r\n", "\n").lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                endPara()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                out += Block.Code(code.toString())
            }
            trimmed.isEmpty() -> endPara()
            trimmed.matches(Regex("^([-*_])\\s*(\\1\\s*){2,}$")) -> { endPara(); out += Block.Rule }
            headingRe.matches(trimmed) -> {
                endPara()
                val m = headingRe.find(trimmed)!!
                out += Block.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            bulletRe.matches(line) -> {
                endPara()
                val m = bulletRe.find(line)!!
                val marker = m.groupValues[2].let { if (it.first().isDigit()) it else "•" }
                out += Block.Bullet(marker, m.groupValues[1].length / 2, m.groupValues[3])
            }
            trimmed.startsWith(">") -> { endPara(); out += Block.Quote(trimmed.removePrefix(">").trim()) }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
        i++
    }
    endPara()
    return out
}

fun inlineMarkdown(text: String, codeColor: Color = OffWhite): AnnotatedString = buildAnnotatedString {
    var i = 0
    fun closing(token: String, from: Int): Int {
        val j = text.indexOf(token, from)
        return if (j > from) j else -1
    }
    while (i < text.length) {
        val rest = text.length - i
        when {
            text[i] == '\\' && rest > 1 && text[i + 1] in "*_`~[]\\" -> { append(text[i + 1]); i += 2 }
            text[i] == '`' -> {
                val end = closing("`", i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = Mono, background = Color(0xFF222222), color = codeColor, fontSize = 0.92.em())) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append('`'); i++ }
            }
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val token = text.substring(i, i + 2)
                val end = closing(token, i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inlineMarkdown(text.substring(i + 2, end), codeColor)) }
                    i = end + 2
                } else { append(token); i += 2 }
            }
            text.startsWith("~~", i) -> {
                val end = closing("~~", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inlineMarkdown(text.substring(i + 2, end), codeColor)) }
                    i = end + 2
                } else { append("~~"); i += 2 }
            }
            (text[i] == '*' || text[i] == '_') && rest > 1 && !text[i + 1].isWhitespace() &&
                (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                val token = text[i].toString()
                val end = closing(token, i + 1)
                if (end > 0 && !text[end - 1].isWhitespace() && (end + 1 >= text.length || !text[end + 1].isLetterOrDigit())) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inlineMarkdown(text.substring(i + 1, end), codeColor)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val close = text.indexOf("](", i)
                val endUrl = if (close > 0) text.indexOf(')', close) else -1
                if (close > 0 && endUrl > 0 && '\n' !in text.substring(i, endUrl)) {
                    withStyle(SpanStyle(color = Red, textDecoration = TextDecoration.Underline)) { append(text.substring(i + 1, close)) }
                    i = endUrl + 1
                } else { append('['); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

private fun Double.em(): TextUnit = (this * 14).sp

@Composable
fun MarkdownText(text: String, color: Color = OffWhite, fontSize: TextUnit = 15.sp) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Paragraph -> Text(inlineMarkdown(b.text), color = color, fontSize = fontSize, lineHeight = fontSize * 1.4f)
                is Block.Heading -> Text(
                    inlineMarkdown(b.text), color = color, fontWeight = FontWeight.Bold,
                    fontSize = when (b.level) { 1 -> fontSize * 1.35f; 2 -> fontSize * 1.2f; else -> fontSize * 1.05f },
                )
                is Block.Bullet -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                    Text(b.marker, color = Gray, fontSize = fontSize, modifier = Modifier.width(if (b.marker == "•") 16.dp else 24.dp))
                    Text(inlineMarkdown(b.text), color = color, fontSize = fontSize, lineHeight = fontSize * 1.4f)
                }
                is Block.Quote -> Row {
                    Box(Modifier.width(3.dp).height(20.dp).background(Gray.copy(alpha = 0.5f)))
                    Text(inlineMarkdown(b.text), color = Gray, fontSize = fontSize, fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 8.dp))
                }
                is Block.Code -> Box(
                    Modifier.fillMaxWidth().background(Color(0xFF161616), RoundedCornerShape(6.dp))
                        .horizontalScroll(rememberScrollState()).padding(10.dp),
                ) {
                    Text(b.text, color = color, fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp, softWrap = false)
                }
                Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(SurfaceHigh))
            }
        }
    }
}
