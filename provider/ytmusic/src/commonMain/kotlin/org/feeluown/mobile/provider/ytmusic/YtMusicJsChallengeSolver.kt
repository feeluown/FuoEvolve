package org.feeluown.mobile.provider.ytmusic

import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import org.feeluown.mobile.provider.core.providerJson

/**
 * Executes the two player.js challenges yt-dlp models as SIG and N.
 *
 * The extraction strategy deliberately keeps the JS execution boundary small:
 * only the discovered transform and its helper declarations are evaluated in
 * QuickJS. This avoids relying on a browser DOM while still executing YouTube's
 * current JavaScript instead of reimplementing the transform operations in Kotlin.
 */
internal class YtMusicJsChallengeSolver {
    private val signaturePrograms = mutableMapOf<Int, String>()
    private val nPrograms = mutableMapOf<Int, Pair<String, String>>()
    private val nResults = mutableMapOf<Pair<Int, String>, String>()

    fun signatureTimestamp(playerJs: String): Int? =
        Regex("""signatureTimestamp\s*[:=]\s*(\d+)""")
            .find(playerJs)?.groupValues?.getOrNull(1)?.toIntOrNull()

    suspend fun solveSignature(playerJs: String, challenge: String): String? {
        val key = playerJs.hashCode()
        val program = signaturePrograms[key] ?: extractSignatureProgram(playerJs)?.also {
            signaturePrograms[key] = it
        } ?: return null
        return execute(program, SIGNATURE_ENTRY_POINT, challenge)
    }

    suspend fun solveN(playerJs: String, challenge: String): String? {
        val key = playerJs.hashCode()
        nResults[key to challenge]?.let { return it }
        val (program, functionName) = nPrograms[key] ?: extractNProgram(playerJs)?.also {
            nPrograms[key] = it
        } ?: return null
        return execute(program, functionName, challenge)?.takeIf { it.isNotBlank() }?.also {
            nResults[key to challenge] = it
        }
    }

    private suspend fun execute(program: String, functionName: String, argument: String): String? =
        runCatching {
            quickJs(Dispatchers.Default) {
                evaluationTimeoutMillis = 2_000
                evaluate<String>(
                    buildString {
                        append(program)
                        append('\n')
                        append(functionName)
                        append('(')
                        append(quote(argument))
                        append(')')
                    },
                )
            }
        }.getOrNull()

    private fun extractSignatureProgram(playerJs: String): String? {
        val (functionName, extraParams) = signatureFunction(playerJs) ?: return null
        val function = extractFunction(playerJs, functionName) ?: return null
        val declarations = linkedSetOf<String>()

        // Modern signature transforms typically reference a small helper object.
        Regex("""([A-Za-z0-9_$]{2,})[.\[]""").findAll(function).forEach { match ->
            val name = match.groupValues[1]
            if (name != functionName && name !in JS_GLOBALS) {
                extractDeclaration(playerJs, name)?.let(declarations::add)
            }
        }

        // Some player versions keep a shared split-string lookup in a global.
        Regex("""(?:var|let|const)\s+[A-Za-z0-9_$]+\s*=\s*["'][^"']*["']\.split\(["'][;{]["']\)\s*;""")
            .find(playerJs)?.value?.let(declarations::add)

        return buildString {
            declarations.forEach { appendLine(it) }
            appendLine(function)
            append("function $SIGNATURE_ENTRY_POINT(a){return $functionName(")
            append(extraParams)
            append("a);}")
        }
    }

    private fun signatureFunction(playerJs: String): Pair<String, String>? {
        val patterns = listOf(
            Regex("""\b[A-Za-z0-9_$]+&&\([A-Za-z0-9_$]+=([A-Za-z0-9_$]{2,})\((\d+,)decodeURIComponent\([A-Za-z0-9_$]+\)\)"""),
            Regex("""\b[A-Za-z0-9_$]+&&\([A-Za-z0-9_$]+=([A-Za-z0-9_$]{2,})\(decodeURIComponent\([A-Za-z0-9_$]+\)\)"""),
            Regex("""\bm=([A-Za-z0-9_$]{2,})\(decodeURIComponent\(h\.s\)\)"""),
            Regex("""\bc&&\(c=([A-Za-z0-9_$]{2,})\(decodeURIComponent\(c\)\)"""),
            Regex("""(?:\b|[^A-Za-z0-9_$])([A-Za-z0-9_$]{2,})\s*=\s*function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*["']["']\s*\)"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(playerJs) ?: continue
            return match.groupValues[1] to match.groupValues.getOrNull(2).orEmpty()
        }
        return null
    }

    private fun extractNProgram(playerJs: String): Pair<String, String>? {
        val reference = nFunctionReference(playerJs) ?: return null
        val functionName = resolveArrayFunction(playerJs, reference.first, reference.second) ?: reference.first
        val original = extractFunction(playerJs, functionName) ?: return null
        val function = stripNGuard(original)
        val declarations = linkedSetOf<String>()

        Regex("""([A-Za-z0-9_$]{2,})[.\[]""").findAll(function).forEach { match ->
            val name = match.groupValues[1]
            if (name != functionName && name !in JS_GLOBALS) {
                extractDeclaration(playerJs, name)?.let(declarations::add)
            }
        }

        return buildString {
            declarations.forEach { appendLine(it) }
            append(function)
        } to functionName
    }

    private fun nFunctionReference(playerJs: String): Pair<String, Int?>? {
        val patterns = listOf(
            Regex("""\.get\(["']n["']\)\)\s*&&\s*\([A-Za-z0-9_$]+=([A-Za-z0-9_$]+)(?:\[(\d+)])?\([A-Za-z0-9_$]+\)"""),
            Regex("""String\.fromCharCode\(110\).*?&&\s*\([A-Za-z0-9_$]+=([A-Za-z0-9_$]+)(?:\[(\d+)])?\([A-Za-z0-9_$]+\)"""),
            Regex("""[A-Za-z0-9_$]+=["']nn["']\[\+[A-Za-z0-9_$]+\.[A-Za-z0-9_$]+].*?&&\s*\([A-Za-z0-9_$]+=([A-Za-z0-9_$]+)(?:\[(\d+)])?\([A-Za-z0-9_$]+\)"""),
            Regex("""([A-Za-z0-9_$]{2,})=function[\s\S]{0,1200}?return [A-Z]\[\d+]"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(playerJs) ?: continue
            return match.groupValues[1] to match.groupValues.getOrNull(2)?.toIntOrNull()
        }
        return null
    }

    private fun resolveArrayFunction(playerJs: String, name: String, index: Int?): String? {
        if (index == null) return name
        val declaration = Regex(
            """(?:var|let|const)\s+${Regex.escape(name)}\s*=\s*\[([^]]+)]""",
        ).find(playerJs)?.groupValues?.getOrNull(1) ?: return null
        return declaration.split(',').getOrNull(index)?.trim()
            ?.removePrefix("function ")
            ?.takeWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
            ?.takeIf { it.isNotBlank() }
    }

    private fun stripNGuard(function: String): String = function.replaceFirst(
        Regex(""";?\s*if\s*\(\s*typeof\s+[A-Za-z0-9_$]+\s*={2,3}\s*(["'])undefined\1\s*\)\s*return\s+[A-Za-z0-9_$]+\s*;"""),
        ";",
    )

    private fun extractFunction(source: String, name: String): String? {
        val escaped = Regex.escape(name)
        val assignment = Regex("""(?:var\s+)?$escaped\s*=\s*function\s*\(""").find(source)
        if (assignment != null) {
            val functionIndex = source.indexOf("function", assignment.range.first)
            val brace = source.indexOf('{', functionIndex)
            val end = matchClosing(source, brace, '{', '}') ?: return null
            val prefix = if (source.substring(assignment.range.first, functionIndex).trimStart().startsWith("var ")) {
                ""
            } else {
                "var "
            }
            return prefix + source.substring(assignment.range.first, end + 1) + ";"
        }

        val declaration = Regex("""function\s+$escaped\s*\(""").find(source) ?: return null
        val brace = source.indexOf('{', declaration.range.first)
        val end = matchClosing(source, brace, '{', '}') ?: return null
        return source.substring(declaration.range.first, end + 1)
    }

    private fun extractDeclaration(source: String, name: String): String? {
        val escaped = Regex.escape(name)
        val match = Regex("""(?:var|let|const)\s+$escaped\s*=""").find(source) ?: return null
        var index = match.range.last + 1
        var round = 0
        var square = 0
        var curly = 0
        var quote: Char? = null
        var escapedChar = false
        var lineComment = false
        var blockComment = false
        while (index < source.length) {
            val ch = source[index]
            val next = source.getOrNull(index + 1)
            if (lineComment) {
                if (ch == '\n') lineComment = false
                index++
                continue
            }
            if (blockComment) {
                if (ch == '*' && next == '/') {
                    blockComment = false
                    index += 2
                } else index++
                continue
            }
            if (quote != null) {
                if (escapedChar) escapedChar = false
                else if (ch == '\\') escapedChar = true
                else if (ch == quote) quote = null
                index++
                continue
            }
            when {
                ch == '/' && next == '/' -> { lineComment = true; index += 2; continue }
                ch == '/' && next == '*' -> { blockComment = true; index += 2; continue }
                ch == '\'' || ch == '"' || ch == '`' -> quote = ch
                ch == '(' -> round++
                ch == ')' -> round--
                ch == '[' -> square++
                ch == ']' -> square--
                ch == '{' -> curly++
                ch == '}' -> curly--
                ch == ';' && round <= 0 && square <= 0 && curly <= 0 ->
                    return source.substring(match.range.first, index + 1)
            }
            index++
        }
        return null
    }

    private fun matchClosing(source: String, openIndex: Int, open: Char, close: Char): Int? {
        if (openIndex !in source.indices || source[openIndex] != open) return null
        var depth = 0
        var quote: Char? = null
        var escapedChar = false
        var lineComment = false
        var blockComment = false
        var index = openIndex
        while (index < source.length) {
            val ch = source[index]
            val next = source.getOrNull(index + 1)
            if (lineComment) {
                if (ch == '\n') lineComment = false
                index++
                continue
            }
            if (blockComment) {
                if (ch == '*' && next == '/') {
                    blockComment = false
                    index += 2
                } else index++
                continue
            }
            if (quote != null) {
                if (escapedChar) escapedChar = false
                else if (ch == '\\') escapedChar = true
                else if (ch == quote) quote = null
                index++
                continue
            }
            when {
                ch == '/' && next == '/' -> { lineComment = true; index += 2; continue }
                ch == '/' && next == '*' -> { blockComment = true; index += 2; continue }
                ch == '\'' || ch == '"' || ch == '`' -> quote = ch
                ch == open -> depth++
                ch == close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun quote(value: String): String = providerJson.encodeToString(
        JsonPrimitive.serializer(),
        JsonPrimitive(value),
    )

    private companion object {
        const val SIGNATURE_ENTRY_POINT = "__fuo_sig"
        val JS_GLOBALS = setOf(
            "Array", "Date", "Error", "JSON", "Math", "Object", "RegExp", "String",
            "decodeURIComponent", "encodeURIComponent", "parseInt", "undefined", "window",
        )
    }
}
