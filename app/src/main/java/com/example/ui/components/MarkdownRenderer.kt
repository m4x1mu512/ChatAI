package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
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
import com.example.R
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.semanticColors

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletItem(val text: String) : MdBlock
    data class OrderedItem(val number: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class CodeBlock(val language: String, val code: String) : MdBlock
    data class MathBlock(val tex: String) : MdBlock
    data object Divider : MdBlock
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    onCopied: (() -> Unit)? = null,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = parseInlineMarkdown(block.text, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant),
                        style = style,
                        color = textColor,
                    )
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant),
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor,
                    )
                }
                is MdBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.padding(start = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(16.dp),
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant),
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor,
                        )
                    }
                }
                is MdBlock.OrderedItem -> {
                    Row(
                        modifier = Modifier.padding(start = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${block.number}.",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant),
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor,
                        )
                    }
                }
                is MdBlock.Quote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
                            .padding(vertical = 8.dp, horizontal = 10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = parseInlineMarkdown(block.text, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant),
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = textColor.copy(alpha = 0.9f),
                        )
                    }
                }
                is MdBlock.CodeBlock -> {
                    CodeBlockCard(
                        language = block.language,
                        code = block.code,
                        onCopied = onCopied,
                    )
                }
                is MdBlock.MathBlock -> {
                    LatexMathCard(tex = block.tex)
                }
                is MdBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockCard(
    language: String,
    code: String,
    onCopied: (() -> Unit)?,
) {
    val clipboard = LocalClipboardManager.current
    val highlighted = remember(code, language) { highlightSyntax(code) }
    val semantic = MaterialTheme.semanticColors

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = semantic.codeSurface,
        border = BorderStroke(1.dp, semantic.codeBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(semantic.codeHeader)
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Code,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = language.ifBlank { "code" },
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                        color = Color(0xFFCBD5E1),
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        onCopied?.invoke()
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy_code),
                        tint = Color(0xFFCBD5E1),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = highlighted,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = MonoFontFamily,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun LatexMathCard(tex: String) {
    var showRaw by remember { mutableStateOf(false) }
    val rendered = remember(tex) { formatLatexExpression(tex) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = if (showRaw) tex else rendered,
                    style = if (showRaw) {
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily)
                    } else {
                        MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                        )
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = { showRaw = !showRaw },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Functions,
                    contentDescription = "Toggle LaTeX source",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun parseMarkdownBlocks(input: String): List<MdBlock> {
    val lines = input.replace("\r\n", "\n").split("\n")
    val result = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val codeSb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                if (codeSb.isNotEmpty()) codeSb.append('\n')
                codeSb.append(lines[i])
                i++
            }
            result += MdBlock.CodeBlock(lang, codeSb.toString())
            i++
            continue
        }

        if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
            val endToken = if (trimmed.startsWith("$$")) "$$" else "\\]"
            val firstContent = trimmed.removePrefix("$$").removePrefix("\\[")
            if (firstContent.endsWith(endToken) && firstContent.length > endToken.length) {
                result += MdBlock.MathBlock(firstContent.removeSuffix(endToken).trim())
                i++
                continue
            }
            val mathSb = StringBuilder(firstContent)
            i++
            while (i < lines.size && !lines[i].trim().endsWith(endToken)) {
                if (mathSb.isNotEmpty()) mathSb.append('\n')
                mathSb.append(lines[i])
                i++
            }
            if (i < lines.size) {
                val tail = lines[i].trim().removeSuffix(endToken)
                if (tail.isNotBlank()) {
                    if (mathSb.isNotEmpty()) mathSb.append('\n')
                    mathSb.append(tail)
                }
            }
            result += MdBlock.MathBlock(mathSb.toString().trim())
            i++
            continue
        }

        when {
            trimmed.isEmpty() -> Unit
            trimmed == "---" || trimmed == "***" -> result += MdBlock.Divider
            trimmed.startsWith("#### ") -> result += MdBlock.Heading(4, trimmed.removePrefix("#### "))
            trimmed.startsWith("### ") -> result += MdBlock.Heading(3, trimmed.removePrefix("### "))
            trimmed.startsWith("## ") -> result += MdBlock.Heading(2, trimmed.removePrefix("## "))
            trimmed.startsWith("# ") -> result += MdBlock.Heading(1, trimmed.removePrefix("# "))
            trimmed.startsWith("> ") -> result += MdBlock.Quote(trimmed.removePrefix("> "))
            trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                result += MdBlock.BulletItem(trimmed.drop(2))
            Regex("""^\d+\.\s+.*""").matches(trimmed) -> {
                val num = trimmed.substringBefore(".")
                val body = trimmed.substringAfter(".").trimStart()
                result += MdBlock.OrderedItem(num, body)
            }
            else -> {
                val para = StringBuilder(line)
                while (i + 1 < lines.size) {
                    val next = lines[i + 1].trim()
                    if (next.isEmpty() || next.startsWith("#") || next.startsWith("```") ||
                        next.startsWith("$$") || next.startsWith("\\[") ||
                        next.startsWith("- ") || next.startsWith("* ") ||
                        next.startsWith("> ") || next == "---" ||
                        Regex("""^\d+\.\s+.*""").matches(next)
                    ) {
                        break
                    }
                    i++
                    para.append('\n').append(lines[i])
                }
                result += MdBlock.Paragraph(para.toString())
            }
        }
        i++
    }
    return result
}

private fun parseInlineMarkdown(
    text: String,
    linkColor: Color,
    codeBg: Color,
): AnnotatedString = buildAnnotatedString {
    val tokenRegex = Regex(
        """(\[([^]]+)\]\((https?://[^)]+)\))|(`([^`]+)`)|(\*\*([^*]+)\*\*)|(\*([^*]+)\*)|(~~([^~]+)~~)|(\$([^$]+)\$)|(\\\((.+?)\\\))|(https?://[^\s<>]+)"""
    )
    var cursor = 0
    for (match in tokenRegex.findAll(text)) {
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        val g = match.groupValues
        when {
            g[1].isNotEmpty() -> { // [label](url)
                withLink(
                    LinkAnnotation.Url(
                        url = g[3],
                        styles = TextLinkStyles(
                            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                        ),
                    ),
                ) {
                    append(g[2])
                }
            }
            g[4].isNotEmpty() -> { // `code`
                withStyle(
                    SpanStyle(
                        fontFamily = MonoFontFamily,
                        background = codeBg.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                    ),
                ) {
                    append(" ${g[5]} ")
                }
            }
            g[6].isNotEmpty() -> { // **bold**
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(g[7])
                }
            }
            g[8].isNotEmpty() -> { // *italic*
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(g[9])
                }
            }
            g[10].isNotEmpty() -> { // ~~strike~~
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(g[11])
                }
            }
            g[12].isNotEmpty() -> { // $inline math$
                withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, color = linkColor)) {
                    append(formatLatexExpression(g[13]))
                }
            }
            g[14].isNotEmpty() -> { // \(inline math\)
                withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, color = linkColor)) {
                    append(formatLatexExpression(g[15]))
                }
            }
            g[16].isNotEmpty() -> { // bare url
                val url = g[16].trimEnd('.', ',', ')', ';')
                withLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                        ),
                    ),
                ) {
                    append(url)
                }
                val trailing = g[16].removePrefix(url)
                if (trailing.isNotEmpty()) append(trailing)
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

private fun highlightSyntax(code: String): AnnotatedString = buildAnnotatedString {
    val keywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data",
        "return", "if", "else", "when", "for", "while", "import", "package", "suspend",
        "override", "private", "public", "internal", "const", "def", "async", "await",
        "function", "export", "default", "try", "catch", "throw", "true", "false", "null",
        "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE",
    )
    val tokenRegex = Regex("""(//[^\n]*|#[^\n]*)|("([^"\\]|\\.)*"|'([^'\\]|\\.)*')|\b(\d+(?:\.\d+)?)\b|\b([A-Za-z_][A-Za-z0-9_]*)\b""")
    var cursor = 0
    for (m in tokenRegex.findAll(code)) {
        if (m.range.first > cursor) {
            withStyle(SpanStyle(color = Color(0xFFE2E8F0))) {
                append(code.substring(cursor, m.range.first))
            }
        }
        val color = when {
            m.groupValues[1].isNotEmpty() -> Color(0xFF64748B) // comments
            m.groupValues[2].isNotEmpty() -> Color(0xFF86EFAC) // strings
            m.groupValues[5].isNotEmpty() -> Color(0xFFFBBF24) // numbers
            m.groupValues[6] in keywords -> Color(0xFF93C5FD) // keywords
            else -> Color(0xFFE2E8F0)
        }
        withStyle(SpanStyle(color = color)) {
            append(m.value)
        }
        cursor = m.range.last + 1
    }
    if (cursor < code.length) {
        withStyle(SpanStyle(color = Color(0xFFE2E8F0))) {
            append(code.substring(cursor))
        }
    }
}

internal fun formatLatexExpression(raw: String): String {
    var out = raw.trim()
    val replacements = listOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ",
        "\\pi" to "π", "\\sigma" to "σ", "\\phi" to "φ", "\\omega" to "ω",
        "\\Delta" to "Δ", "\\Sigma" to "Σ", "\\Omega" to "Ω", "\\Pi" to "Π",
        "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\infty" to "∞",
        "\\sqrt" to "√", "\\pm" to "±", "\\times" to "×", "\\cdot" to "·",
        "\\div" to "÷", "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥",
        "\\ge" to "≥", "\\neq" to "≠", "\\approx" to "≈", "\\equiv" to "≡",
        "\\rightarrow" to "→", "\\to" to "→", "\\leftarrow" to "←",
        "\\Rightarrow" to "⇒", "\\forall" to "∀", "\\exists" to "∃",
        "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂", "\\partial" to "∂",
        "\\nabla" to "∇", "\\cdot" to "·",
    )
    for ((k, v) in replacements) {
        out = out.replace(k, v)
    }
    // Convert \frac{a}{b} -> (a)/(b)
    out = out.replace(Regex("""\\frac\{([^}]+)\}\{([^}]+)\}""")) { m ->
        "(${m.groupValues[1]})/(${m.groupValues[2]})"
    }
    // Convert simple superscripts ^2, ^3, ^n, ^{...}
    val supMap = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', 'n' to 'ⁿ', '+' to '⁺', '-' to '⁻')
    val subMap = mapOf('0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉', 'i' to 'ᵢ', 'n' to 'ₙ')
    out = out.replace(Regex("""\^\{([^}]+)\}""")) { m ->
        m.groupValues[1].map { supMap[it] ?: it }.joinToString("")
    }
    out = out.replace(Regex("""\^([0-9n])""")) { m ->
        (supMap[m.groupValues[1][0]] ?: m.groupValues[1][0]).toString()
    }
    out = out.replace(Regex("""_\{([^}]+)\}""")) { m ->
        m.groupValues[1].map { subMap[it] ?: it }.joinToString("")
    }
    out = out.replace(Regex("""_([0-9in])""")) { m ->
        (subMap[m.groupValues[1][0]] ?: m.groupValues[1][0]).toString()
    }
    return out.replace("\\left", "").replace("\\right", "").replace("{", "(").replace("}", ")")
}
