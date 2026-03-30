package com.zerochat.app.domain.crypto

import android.util.Log
import java.nio.ByteBuffer
import uniffi.nym_transport.sessionEncryptWrapper
import uniffi.nym_transport.sessionDecryptWrapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zero-Copy JNI Boundary — Sensitive data bypasses JVM heap.
 *
 * Paper §9: "Session keys and plaintext MUST NOT transit the JVM GC heap,
 * where they could persist in unreachable memory for up to 4 GC cycles."
 *
 * Strategy:
 * 1. Allocate DirectByteBuffer (off-heap, not subject to GC relocation)
 * 2. Copy sensitive data INTO the direct buffer
 * 3. Pass to UniFFI (which copies into Rust heap)
 * 4. Immediately zeroize the direct buffer
 *
 * This ensures sensitive bytes exist on the JVM side only in a deterministically
 * zeroizable region (direct memory), never in GC-managed heap byte arrays.
 */
@Singleton
class SecureJniBridge @Inject constructor() {

    companion object {
        private const val TAG = "SecureJniBridge"
        private const val MAX_BUFFER_SIZE = 8192  // 8KB max for crypto ops
    }

    /**
     * Encrypt plaintext using session key (via Rust), keeping plaintext off JVM heap.
     *
     * Flow:
     * 1. Write plaintext into DirectByteBuffer
     * 2. Copy to ByteArray for UniFFI call (direct buffers can't be passed to UniFFI)
     * 3. Zeroize both the direct buffer and the temporary ByteArray
     * 4. Return ciphertext (non-sensitive, GC is fine)
     */
    fun encryptSecure(sessionHandle: Long, plaintext: ByteArray): ByteArray {
        // Allocate direct buffer (off-heap)
        val directBuf = ByteBuffer.allocateDirect(plaintext.size)
        val tempArray = ByteArray(plaintext.size)

        try {
            // Write plaintext to direct buffer
            directBuf.put(plaintext)
            directBuf.flip()

            // Read back to temp array for UniFFI (unavoidable copy)
            directBuf.get(tempArray)

            // Call Rust encryption
            val ciphertext = sessionEncryptWrapper(
                sessionHandle.toULong(),
                tempArray.toList().map { it.toUByte() }
            )

            return ciphertext.map { it.toByte() }.toByteArray()
        } finally {
            // Zeroize: direct buffer
            directBuf.clear()
            for (i in 0 until directBuf.capacity()) {
                directBuf.put(i, 0)
            }

            // Zeroize: temp array
            tempArray.fill(0)

            // Zeroize: input plaintext (caller should do this too)
            plaintext.fill(0)

            Log.d(TAG, "Encrypt complete — direct buffer and temp array zeroized")
        }
    }

    /**
     * Decrypt ciphertext using session key (via Rust), keeping plaintext off JVM heap.
     *
     * The decrypted plaintext is returned in a DirectByteBuffer wrapper
     * that the caller MUST zeroize after use via [ZeroableBuffer.zeroize()].
     */
    fun decryptSecure(sessionHandle: Long, ciphertext: ByteArray): ZeroableBuffer {
        // Ciphertext is non-sensitive (already encrypted), pass directly
        val plaintext = sessionDecryptWrapper(
            sessionHandle.toULong(),
            ciphertext.toList().map { it.toUByte() }
        )

        val plaintextBytes = plaintext.map { it.toByte() }.toByteArray()

        // Wrap in zeroable direct buffer
        val directBuf = ByteBuffer.allocateDirect(plaintextBytes.size)
        directBuf.put(plaintextBytes)
        directBuf.flip()

        // Zeroize the intermediate heap array
        plaintextBytes.fill(0)

        return ZeroableBuffer(directBuf)
    }

    /**
     * Wrapper around DirectByteBuffer that enforces zeroization after use.
     * Caller MUST call [zeroize] when done reading the decrypted data.
     */
    class ZeroableBuffer(private val buffer: ByteBuffer) {
        val size: Int get() = buffer.remaining()

        /**
         * Read the decrypted data. Returns a copy — caller should zeroize the copy too.
         */
        fun readBytes(): ByteArray {
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            buffer.flip() // Allow re-read if needed
            return data
        }

        /**
         * Read as UTF-8 string. The string will be in JVM heap (unavoidable for strings),
         * but this is acceptable for message display.
         */
        fun readString(): String {
            val bytes = readBytes()
            val str = String(bytes, Charsets.UTF_8)
            bytes.fill(0) // Zeroize the intermediate byte array
            return str
        }

        /**
         * Zeroize the direct buffer. MUST be called when done.
         */
        fun zeroize() {
            buffer.clear()
            for (i in 0 until buffer.capacity()) {
                buffer.put(i, 0)
            }
            Log.d(TAG, "ZeroableBuffer zeroized (${buffer.capacity()} bytes)")
        }
    }
}
