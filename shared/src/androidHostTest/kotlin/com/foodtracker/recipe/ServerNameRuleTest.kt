package com.foodtracker.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * functions/src/core/inputSafety.ts `normalizeName`, ported line by line with java.util.regex
 * (which has the same Unicode script and category classes as the server's `u` regexes), so the
 * client's cleaning can be checked against the server's rules without running TypeScript. Kept
 * in the host test because it needs the JVM's regex and normaliser; the server file is
 * authoritative, and the shared fixture (functions/test/fixtures/names.json) holds the server's
 * own verdicts, which this port must reproduce.
 */
internal object ServerNameRule {
    const val OK = "ok"
    private const val NAME_MAX_LENGTH = 40
    private const val RAW_MAX_LENGTH = 200

    private val LATIN = Regex("\\p{IsLatin}")
    private val CYRILLIC = Regex("\\p{IsCyrillic}")
    private val GREEK = Regex("\\p{IsGreek}")
    private val LETTER = Regex("\\p{L}")
    private val MARK = Regex("\\p{M}")
    private val DIGIT = Regex("\\p{Nd}")
    private val ASCII_DIGIT = Regex("[0-9]")
    private val UNSPACED = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsThai}\\p{IsLao}\\p{IsKhmer}\\p{IsMyanmar}]")
    private val SYLLABIC =
        Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsThai}\\p{IsLao}\\p{IsKhmer}\\p{IsMyanmar}\\p{IsYi}]")
    private val APOSTROPHES = Regex("[\\x{2018}\\x{2019}\\x{201B}\\x{02BC}\\x{02B9}\\x{0060}\\x{00B4}\\x{2032}]")
    private val DASHES = Regex("[\\x{2010}-\\x{2015}\\x{2212}\\x{FE58}\\x{FE63}\\x{FF0D}]")
    private val INVISIBLE = Regex(
        "[\\p{Cf}\\x{FE00}-\\x{FE0F}\\x{E0100}-\\x{E01EF}\\x{115F}\\x{1160}\\x{3164}\\x{FFA0}]|[\\x{180B}-\\x{180F}]|\\x{034F}"
    )
    private val SPACES = Regex("[\\p{Cc}\\p{Zs}\\p{Zl}\\p{Zp}]+")

    val SUSPICIOUS = setOf("MARKS", "MIXED_SCRIPT", "ENCODED", "REPEATED", "SPACED_LETTERS", "INSTRUCTION")

    private fun nfkc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC)
    private fun codePoints(s: String): List<String> = s.codePoints().toArray().map { String(Character.toChars(it)) }
    private fun String.test(r: Regex) = r.containsMatchIn(this)

    fun fold(s: String): String = Normalizer.normalize(
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "").lowercase(Locale.ROOT),
        Normalizer.Form.NFC
    )

    private fun confusable(c: String): String? = when {
        !c.test(LETTER) -> null
        c.test(LATIN) -> "Latin"
        c.test(CYRILLIC) -> "Cyrillic"
        c.test(GREEK) -> "Greek"
        else -> null
    }

    private val HOMOGLYPHS = mapOf(
        "а" to "a", "в" to "b", "е" to "e", "к" to "k", "м" to "m", "н" to "h", "о" to "o", "р" to "p", "с" to "c",
        "т" to "t", "у" to "y", "х" to "x", "і" to "i", "ј" to "j", "ѕ" to "s", "ԁ" to "d", "ӏ" to "l", "ԛ" to "q",
        "ԝ" to "w", "ү" to "y", "һ" to "h", "ʏ" to "y",
        "α" to "a", "β" to "b", "ε" to "e", "η" to "h", "ι" to "i", "κ" to "k", "ν" to "v", "ο" to "o", "ρ" to "p",
        "τ" to "t", "υ" to "u", "χ" to "x", "ζ" to "z"
    )
    private val LEET = mapOf("0" to "o", "1" to "i", "3" to "e", "4" to "a", "5" to "s", "7" to "t")

    private fun skeleton(word: String): String {
        val f = fold(word)
        val leet = f.test(LETTER) && f.test(ASCII_DIGIT)
        return codePoints(f).joinToString("") { c -> HOMOGLYPHS[c] ?: (if (leet) LEET[c] ?: c else c) }
    }

    private val STEMS = listOf(
        "ignor", "игнор", "ігнор", "disregard", "jailbreak", "overrid",
        "instruc", "instruk", "istruz", "anweisung", "инструкц", "інструкц",
        "prompt", "promt", "промпт", "промт", "systemprompt", "систем",
        "assistant", "assistent", "asystent", "ассистент", "асистент",
        "chatgpt", "chatbot", "openai", "llm"
    )
    private val WORDS = setOf(
        "system", "systeme", "systemet",
        "forget", "vergiss", "vergessen", "oublie", "oubliez", "olvida", "olvide", "dimentica", "zapomnij",
        "glem", "glom", "забудь", "забудьте", "pretend", "roleplay", "bypass",
        "json", "schema", "html", "http", "https", "www", "markdown", "output", "gpt"
    )

    private fun isInstructionWord(word: String): Boolean =
        setOf(fold(word), skeleton(word)).any { w -> w in WORDS || STEMS.any { w.startsWith(it) } }

    private fun looksEncoded(part: String): Boolean {
        if (part.length >= 12 && Regex("^[0-9a-f]+$", RegexOption.IGNORE_CASE).matches(part) && part.test(ASCII_DIGIT) &&
            Regex("[a-f]", RegexOption.IGNORE_CASE).containsMatchIn(part)
        ) {
            return true
        }
        val chars = codePoints(part)
        val inner = chars.drop(1)
        if (part.test(Regex("\\p{Ll}")) && inner.count { it.test(Regex("\\p{Lu}")) } >= 3) return true
        var caseSwitches = 0
        var letterDigitSwitches = 0
        var digitInsideLetters = 0
        for (i in 1 until chars.size) {
            val a = chars[i - 1]
            val b = chars[i]
            if (a != a.uppercase(Locale.ROOT) && b != b.lowercase(Locale.ROOT)) caseSwitches++
            if ((a.test(DIGIT) && b.test(LETTER)) || (a.test(LETTER) && b.test(DIGIT))) letterDigitSwitches++
            if (i + 1 < chars.size && a.test(LETTER) && b.test(DIGIT) && chars[i + 1].test(LETTER)) digitInsideLetters++
        }
        return caseSwitches >= 3 || letterDigitSwitches >= 5 || digitInsideLetters >= 2
    }

    /** [OK] and the normalised name, or the rule that refused it. */
    fun check(raw: String): Pair<String, String?> {
        if (raw.length > RAW_MAX_LENGTH) return "TOO_LONG" to null
        val name = nfkc(
            nfkc(raw)
                .replace("⁄", "/")
                .replace("٪", "%")
                .replace("٫", ".")
                .replace(APOSTROPHES, "'")
                .replace(DASHES, "-")
                .replace(INVISIBLE, "")
                .replace(SPACES, " ")
                .replace(Regex("-{2,}"), "-")
                .trim(' ')
        )
        if (name.isEmpty()) return "EMPTY" to null
        val chars = codePoints(name)
        if (chars.size > NAME_MAX_LENGTH) return "TOO_LONG" to null

        fun wordy(x: String) = x.test(LETTER) || x.test(MARK)
        var letters = 0
        var marks = 0
        var base = ""
        var run = 0
        for (i in chars.indices) {
            val c = chars[i]
            val prev = chars.getOrNull(i - 1) ?: ""
            val next = chars.getOrNull(i + 1) ?: ""
            if (c.test(LETTER)) {
                letters++
                base = c
                run = 0
                continue
            }
            if (c.test(MARK)) {
                run++
                marks++
                val max = if (confusable(base) != null) 1 else 4
                if (base.isEmpty() || run > max || c == prev) return "MARKS" to null
                continue
            }
            base = ""
            run = 0
            if (c.test(DIGIT) || c == " " || c == "-") continue
            if (c == "'" && wordy(prev) && next.test(LETTER)) continue
            if (c == "&" && i > 0 && i < chars.size - 1 && (wordy(prev) || prev == " ") && (next.test(LETTER) || next == " ")) continue
            if (c == "." && wordy(prev) && (next == " " || next.test(DIGIT))) continue
            if (c == "%" && (prev.test(DIGIT) || (prev == " " && (chars.getOrNull(i - 2) ?: "").test(DIGIT)))) continue
            if ((c == "," || c == "." || c == "/") && prev.test(DIGIT) && next.test(DIGIT)) continue
            return "CHARACTERS" to null
        }
        if (marks * 5 > (letters + marks) * 3) return "MARKS" to null

        val words = name.split(" ")
        if (words.size > 8) return "WORDS" to null
        val counts = mutableMapOf<String, Int>()
        var singleLetterRun = 0
        for (word in words) {
            val parts = word.split("-").filter { it.isNotEmpty() }
            for (part in parts) {
                if (codePoints(part).size > 28 && !part.test(UNSPACED)) return "WORD_LENGTH" to null
                if (codePoints(part).mapNotNull(::confusable).toSet().size > 1) return "MIXED_SCRIPT" to null
                if (looksEncoded(part)) return "ENCODED" to null
                if (part.test(Regex("(.)\\1{4,}"))) return "REPEATED" to null
                if (isInstructionWord(part)) return "INSTRUCTION" to null
            }
            if (parts.size > 1 && isInstructionWord(parts.joinToString(""))) return "INSTRUCTION" to null
            val key = fold(word)
            if (key.length >= 2) {
                val n = (counts[key] ?: 0) + 1
                counts[key] = n
                if (n >= 3) return "REPEATED" to null
            }
            singleLetterRun =
                if (codePoints(word).size == 1 && word.test(LETTER) && !word.test(SYLLABIC)) singleLetterRun + 1 else 0
            if (singleLetterRun >= 4) return "SPACED_LETTERS" to null
        }
        if (isInstructionWord(name.replace(Regex("[ -]"), "")) && words.any { codePoints(it).size <= 2 }) {
            return "INSTRUCTION" to null
        }
        return OK to name
    }

    fun rule(raw: String): String = check(raw).first
}

/** One row of functions/test/fixtures/names.json. */
private data class NameCase(
    val input: String,
    val clean: String?,
    val allergen: String?,
    val server: String,
    val normalized: String?,
    val cleanServer: String?
)

/** The fixture shared by the server, this module and the iOS check, found from the module dir up. */
private val NAME_CASES: List<NameCase> by lazy {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    var file: File? = null
    while (dir != null && file == null) {
        file = File(dir, "functions/test/fixtures/names.json").takeIf { it.isFile }
        dir = dir.parentFile
    }
    val root = Json.parseToJsonElement((file ?: fail("functions/test/fixtures/names.json not found")).readText()).jsonObject
    root["names"]!!.jsonArray.map { e ->
        val o = e.jsonObject
        fun str(key: String) = o[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        NameCase(str("input")!!, str("clean"), str("allergen"), str("server")!!, str("normalized"), str("cleanServer"))
    }
}

class ServerNameRuleTest {

    @Test
    fun theFixtureIsBigEnoughToMeanSomething() {
        assertTrue(NAME_CASES.size >= 300, "${NAME_CASES.size} cases")
        assertTrue(NAME_CASES.any { it.input.contains('中') || it.input.contains('牛') })
    }

    @Test
    fun thePortGivesTheServersVerdictOnEveryFixtureInput() {
        for (case in NAME_CASES) {
            val (rule, name) = ServerNameRule.check(case.input)
            assertEquals(case.server, rule, "server verdict on ${case.input}")
            if (rule == ServerNameRule.OK) assertEquals(case.normalized, name, "server normalisation of ${case.input}")
        }
    }

    @Test
    fun cleaningGivesTheFixturesOutputs() {
        val wrong = NAME_CASES.mapNotNull { case ->
            val clean = cleanWireName(case.input)
            val allergen = cleanAllergenName(case.input)
            if (clean == case.clean && allergen == case.allergen) {
                null
            } else {
                "${case.input}: clean $clean (want ${case.clean}), allergen $allergen (want ${case.allergen})"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * What cleaning sends is a name the server takes as sent, except names it only refuses as
     * suspicious for reasons cleaning does not rewrite (meta words, encoding, repetition, spaced
     * letters): the app leaves such an item out. Never a refusal for characters or shape.
     */
    @Test
    fun theServerTakesWhatCleaningSendsAsSent() {
        val structural = setOf("TYPE", "EMPTY", "TOO_LONG", "CHARACTERS", "WORDS", "WORD_LENGTH", "MARKS", "MIXED_SCRIPT")
        for (case in NAME_CASES) {
            val clean = case.clean ?: continue
            val (rule, name) = ServerNameRule.check(clean)
            assertEquals(case.cleanServer, rule, "server verdict on the cleaned ${case.input} -> $clean")
            assertTrue(rule !in structural, "$clean refused as $rule")
            if (rule == ServerNameRule.OK) assertEquals(clean, name, "the server keeps what is sent: ${case.input}")
            assertEquals(clean, cleanWireName(clean), "stable: ${case.input}")
        }
    }

    /** Inputs a JSON fixture can't hold, and a few more, still never leave a structural refusal. */
    @Test
    fun cleaningNeverLeavesAStructuralRefusal() {
        val structural = setOf("TYPE", "EMPTY", "TOO_LONG", "CHARACTERS", "WORDS", "WORD_LENGTH", "MARKS", "MIXED_SCRIPT")
        val inputs = listOf("\uD83D", "\uDC00a", "a\uD83D", "Milk \uD83C", "牛\uDB40", "ب" + "َُِّ".repeat(3))
        for (input in inputs) {
            val cleaned = cleanWireName(input) ?: continue
            val rule = ServerNameRule.rule(cleaned)
            assertTrue(rule !in structural, "$input -> $cleaned refused as $rule")
        }
    }

    @Test
    fun suspiciousRulesAreTheOnesTheServerCounts() {
        assertEquals(setOf("MARKS", "MIXED_SCRIPT", "ENCODED", "REPEATED", "SPACED_LETTERS", "INSTRUCTION"), ServerNameRule.SUSPICIOUS)
    }
}
