package io.github.junwen853.cloudtransfer.icloud

import org.json.JSONObject

/**
 * Trimmed string value, "" when the key is missing or JSON null. Android's
 * org.json turns null into the text "null" for optString, which must never be
 * mistaken for a job id or an error message.
 */
fun JSONObject?.text(key: String): String {
    if (this == null || isNull(key)) return ""
    return optString(key).trim()
}
