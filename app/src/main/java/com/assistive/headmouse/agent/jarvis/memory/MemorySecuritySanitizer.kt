package com.assistive.headmouse.agent.jarvis.memory

/**
 * Security & Privacy Sanitizer for J.A.R.V.I.S. Memory 2.0 (Phase 16).
 * Ensures sensitive data such as passwords, OTPs, API keys, authentication tokens,
 * and credit cards are scrubbed before storage to disk or transmission to LLM context.
 */
object MemorySecuritySanitizer {

    // Common API Key prefixes and patterns
    private val API_KEY_PATTERNS = listOf(
        Regex("""(?i)(?:api[_-]?key|secret|token|password|passwd|auth[_-]?token)[\s:=]+["']?([a-zA-Z0-9_\-\.]{12,})["']?"""),
        Regex("""AIza[0-9A-Za-z\-_]{35}"""), // Google / Gemini API key pattern
        Regex("""sk-[a-zA-Z0-9]{20,48}"""), // OpenAI API key pattern
        Regex("""Bearer\s+[a-zA-Z0-9\-\._~+/]+=*"""), // Bearer token
    )

    // OTP patterns (4 to 8 digits explicitly labeled as code or OTP)
    private val OTP_PATTERNS = listOf(
        Regex("""(?i)(?:otp|verification\s+code|one[_-]?time\s+password|security\s+code|code)(?:\s+is)?[\s:=]+(\d{4,8})"""),
        Regex("""\b(?<!\d)(\d{4,8})(?!\d)\b(?=.*(?:otp|verification|one[_-]?time))""", RegexOption.IGNORE_CASE)
    )

    // Credit Card patterns (13 to 19 digits formatted with spaces or dashes)
    private val CARD_PATTERNS = listOf(
        Regex("""\b(?:\d{4}[ -]?){3}\d{1,4}\b"""),
        Regex("""\b\d{15,16}\b""")
    )

    // PIN patterns (4-6 digits preceded by "pin")
    private val PIN_PATTERNS = listOf(
        Regex("""(?i)(?:pin|mpin|security\s+pin)(?:\s+is)?[\s:=]+(\d{4,6})""")
    )

    /**
     * Sanitizes input text, replacing sensitive tokens with [REDACTED_*] placeholders.
     */
    fun sanitize(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var result: String = input

        // 1. Redact API keys & bearer tokens
        for (pattern in API_KEY_PATTERNS) {
            result = pattern.replace(result) { matchResult ->
                val full = matchResult.value
                val captured = matchResult.groupValues.getOrNull(1)
                if (captured != null && captured.length >= 8) {
                    full.replace(captured, "[REDACTED_API_KEY]")
                } else {
                    "[REDACTED_CREDENTIAL]"
                }
            }
        }

        // 2. Redact OTPs & Verification codes
        for (pattern in OTP_PATTERNS) {
            result = pattern.replace(result) { matchResult ->
                val full = matchResult.value
                val captured = matchResult.groupValues.getOrNull(1)
                if (captured != null) {
                    full.replace(captured, "[REDACTED_OTP]")
                } else {
                    "[REDACTED_OTP]"
                }
            }
        }

        // 3. Redact PINs
        for (pattern in PIN_PATTERNS) {
            result = pattern.replace(result) { matchResult ->
                val full = matchResult.value
                val captured = matchResult.groupValues.getOrNull(1)
                if (captured != null) {
                    full.replace(captured, "[REDACTED_PIN]")
                } else {
                    "[REDACTED_PIN]"
                }
            }
        }

        // 4. Redact Credit / Debit Card numbers
        for (pattern in CARD_PATTERNS) {
            result = pattern.replace(result) { matchResult ->
                val digitsOnly = matchResult.value.filter { it.isDigit() }
                if (digitsOnly.length in 13..19) {
                    "[REDACTED_CARD]"
                } else {
                    matchResult.value
                }
            }
        }

        return result
    }

    /**
     * Checks if a string contains known sensitive credential patterns.
     */
    fun containsSensitiveData(input: String?): Boolean {
        if (input.isNullOrBlank()) return false
        return API_KEY_PATTERNS.any { it.containsMatchIn(input) } ||
               OTP_PATTERNS.any { it.containsMatchIn(input) } ||
               PIN_PATTERNS.any { it.containsMatchIn(input) } ||
               CARD_PATTERNS.any { m ->
                   val match = m.find(input)
                   match != null && match.value.filter { it.isDigit() }.length in 13..19
               }
    }
}
