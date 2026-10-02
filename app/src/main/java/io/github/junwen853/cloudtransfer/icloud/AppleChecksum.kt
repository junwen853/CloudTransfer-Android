package io.github.junwen853.cloudtransfer.icloud

import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Apple's content checksum for a file in iCloud Photos:
 * base64(0x01 || SHA1("com.apple.XattrObjectSalt\0com.apple.DataObjectSalt\0" + bytes)).
 *
 * iCloud reports it as resOriginalRes.fileChecksum, and a CPLMaster created
 * from an upload is named after it. Equal checksums mean byte-identical files,
 * so this is the exact duplicate test.
 */
object AppleChecksum {
    private const val SCHEME_V1: Byte = 0x01
    private const val READ_CHUNK = 1024 * 1024
    private val SALT = "com.apple.XattrObjectSalt\u0000com.apple.DataObjectSalt\u0000".toByteArray(Charsets.UTF_8)

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-1").apply { update(SALT) }

    fun finish(digest: MessageDigest): String {
        val sha1 = digest.digest()
        val signature = ByteArray(21)
        signature[0] = SCHEME_V1
        System.arraycopy(sha1, 0, signature, 1, 20)
        return Base64.getEncoder().encodeToString(signature)
    }

    fun of(bytes: ByteArray): String = finish(newDigest().apply { update(bytes) })

    /** Checksum and byte count of a whole stream. [checkCancelled] runs between chunks. */
    fun of(input: InputStream, checkCancelled: () -> Unit = {}): Pair<String, Long> {
        val digest = newDigest()
        val buffer = ByteArray(READ_CHUNK)
        var total = 0L
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
        }
        return finish(digest) to total
    }

    /** The checksum if it is a well-formed scheme-1 Apple checksum, else "". */
    fun normalize(value: String?): String {
        val checksum = value?.trim().orEmpty()
        if (checksum.length != 28) return ""
        val decoded = try {
            Base64.getDecoder().decode(checksum)
        } catch (_: IllegalArgumentException) {
            return ""
        }
        return if (decoded.size == 21 && decoded[0] == SCHEME_V1) checksum else ""
    }
}
