package com.zerochat.app.domain.i2p

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException

/**
 * I2P Stream — Wrapper around a raw TCP socket connected through SAM bridge.
 *
 * After SAM STREAM CONNECT or STREAM ACCEPT succeeds, the underlying
 * socket becomes a raw bidirectional data stream to the peer's I2P destination.
 * All traffic is routed through I2P tunnels automatically.
 */
class I2PStream(
    private val socket: Socket,
    val inputStream: InputStream,
    val outputStream: OutputStream
) {
    companion object {
        private const val TAG = "I2PStream"
    }

    @Volatile
    private var closed = false

    /**
     * Read up to [buffer.size] bytes from the stream.
     * Returns number of bytes read, or -1 on end of stream.
     */
    fun read(buffer: ByteArray): Int {
        if (closed) return -1
        return try {
            inputStream.read(buffer)
        } catch (e: SocketException) {
            Log.w(TAG, "Read failed: ${e.message}")
            -1
        }
    }

    /**
     * Read exactly [length] bytes from the stream.
     * Blocks until all bytes are received or stream ends.
     * Returns the bytes, or null if stream ended prematurely.
     */
    fun readFully(length: Int): ByteArray? {
        if (closed) return null
        val buffer = ByteArray(length)
        var offset = 0
        Log.v(TAG, "Reading $length bytes...")
        while (offset < length) {
            val n = try {
                inputStream.read(buffer, offset, length - offset)
            } catch (e: SocketException) {
                Log.w(TAG, "ReadFully failed at $offset/$length: ${e.message}")
                return null
            }
            if (n < 0) {
                 Log.w(TAG, "ReadFully EOF at $offset/$length")
                 return null
            }
            offset += n
        }
        Log.v(TAG, "Read full $length bytes")
        return buffer
    }

    /**
     * Write all bytes to the stream.
     */
    fun write(data: ByteArray) {
        if (closed) throw SocketException("Stream closed")
        outputStream.write(data)
        outputStream.flush()
    }

    /**
     * Check if the stream is still connected.
     */
    fun isConnected(): Boolean {
        return !closed && socket.isConnected && !socket.isClosed
    }

    /**
     * Close the stream and underlying socket.
     */
    fun close() {
        if (closed) return
        closed = true
        try {
            inputStream.close()
        } catch (e: Exception) { /* ignore */ }
        try {
            outputStream.close()
        } catch (e: Exception) { /* ignore */ }
        try {
            socket.close()
        } catch (e: Exception) { /* ignore */ }
        Log.d(TAG, "Stream closed")
    }
}
