package io.github.junwen853.cloudtransfer.icloud

/** Cookie header helpers. The header keeps Apple's cookie order: name=value; name=value. */
object Cookies {
    const val PHOTOS_PCS = "X-APPLE-WEBAUTH-PCS-Photos"
    private const val WEBAUTH_TOKEN = "X-APPLE-WEBAUTH-TOKEN"
    private const val WEB_SESSION_TOKEN = "X-APPLE-DS-WEB-SESSION-TOKEN"
    private const val WEBAUTH_VALIDATE = "X-APPLE-WEBAUTH-VALIDATE"
    private const val WEBAUTH_TRUST = "X-APPLE-WEBAUTH-HSA-TRUST"

    fun parse(header: String?): LinkedHashMap<String, String> {
        val cookies = LinkedHashMap<String, String>()
        header.orEmpty().split(';').forEach { pair ->
            val trimmed = pair.trim()
            val equals = trimmed.indexOf('=')
            if (equals <= 0) return@forEach
            val name = trimmed.substring(0, equals).trim()
            val value = trimmed.substring(equals + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
        }
        return cookies
    }

    fun toHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun merge(base: String?, additional: String?): String {
        val cookies = parse(base)
        cookies.putAll(parse(additional))
        return toHeader(cookies)
    }

    fun value(header: String?, name: String, allowSuffixed: Boolean = false): String {
        val cookies = parse(header)
        cookies[name]?.let { return unquote(it) }
        if (allowSuffixed) {
            cookies.entries.firstOrNull { it.key.startsWith("$name-") }?.let { return unquote(it.value) }
        }
        return ""
    }

    fun hasPhotosPcs(header: String?): Boolean = value(header, PHOTOS_PCS).isNotEmpty()

    /** Rough strength of a captured cookie set; the strongest candidate wins. */
    fun strength(header: String?): Int {
        var score = parse(header).size
        if (value(header, WEBAUTH_TOKEN).isNotEmpty()) score += 100
        if (value(header, WEB_SESSION_TOKEN).isNotEmpty()) score += 80
        if (value(header, WEBAUTH_VALIDATE).isNotEmpty()) score += 40
        if (value(header, WEBAUTH_TRUST, allowSuffixed = true).isNotEmpty()) score += 20
        if (hasPhotosPcs(header)) score += 200
        return score
    }

    /**
     * Applies Set-Cookie headers from an Apple response to the session header:
     * Apple rotates X-APPLE-* tokens while a session is used. Only Apple session
     * cookies are taken over; an empty or expired value removes the cookie.
     */
    fun applySetCookies(base: String, setCookieHeaders: List<String>): String {
        if (setCookieHeaders.isEmpty()) return base
        val cookies = parse(base)
        var changed = false
        for (raw in setCookieHeaders) {
            val parts = raw.split(';')
            val first = parts.firstOrNull()?.trim().orEmpty()
            val equals = first.indexOf('=')
            if (equals <= 0) continue
            val name = first.substring(0, equals).trim()
            val value = first.substring(equals + 1).trim()
            if (!isAppleSessionCookie(name)) continue
            val expired = parts.drop(1).any { attribute ->
                val trimmed = attribute.trim()
                trimmed.equals("Max-Age=0", ignoreCase = true) ||
                    (trimmed.startsWith("Max-Age=", ignoreCase = true) &&
                        (trimmed.substringAfter('=').trim().toLongOrNull() ?: 1L) <= 0L) ||
                    (trimmed.startsWith("Expires=", ignoreCase = true) && expiresInPast(trimmed.substringAfter('=')))
            }
            if (unquote(value).isEmpty() || expired) {
                if (cookies.remove(name) != null) changed = true
            } else if (cookies[name] != value) {
                cookies[name] = value
                changed = true
            }
        }
        return if (changed) toHeader(cookies) else base
    }

    fun names(header: String?): String = parse(header).keys.joinToString(", ").ifEmpty { "无" }

    private fun isAppleSessionCookie(name: String): Boolean {
        val upper = name.uppercase()
        return upper.startsWith("X-APPLE") || upper.startsWith("X_APPLE") || upper.startsWith("DES") ||
            name == "acn01" || name == "aasp"
    }

    private fun expiresInPast(value: String): Boolean {
        val time = try {
            java.time.ZonedDateTime.parse(value.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        } catch (_: Exception) {
            return false
        }
        return time.toInstant().toEpochMilli() < System.currentTimeMillis()
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1) else value
}
