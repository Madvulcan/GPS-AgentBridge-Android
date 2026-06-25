package com.madvulcan.gpsagentbridge.net

import android.util.Log
import com.madvulcan.gpsagentbridge.data.ServerTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Result of attempting to send a single datagram to one server.
 *
 * - [bytesSent] is the datagram payload length (independent of whether the OS actually
 *   transmitted it; UDP is fire-and-forget, so success means "handed off to the kernel").
 * - [error] is non-null when a syscall failed (host unresolvable, socket error, etc.).
 */
data class SendResult(
    val target: ServerTarget,
    val bytesSent: Int,
    val error: Throwable? = null,
) {
    val isSuccess: Boolean get() = error == null && bytesSent > 0
}

/**
 * Fire-and-forget UDP sender for NMEA datagrams.
 *
 * One [DatagramSocket] is reused across sends — creating a new socket per packet
 * is wasteful and on some devices triggers SELinux denials. The socket is bound
 * to an ephemeral local port on first use.
 *
 * All sends happen on [Dispatchers.IO]. If a host is unresolvable, that single
 * target fails fast (default DNS timeout is short) without blocking other targets.
 */
open class UdpSender {

    private var socket: DatagramSocket? = null

    @Synchronized
    private fun ensureSocket(): DatagramSocket {
        return socket ?: DatagramSocket().also {
            socket = it
            it.broadcast = false
            // Don't block forever on send — UDP send is normally non-blocking anyway,
            // but set SO_SNDBUF to a reasonable size for the small NMEA payload.
            it.sendBufferSize = 4096
        }
    }

    /**
     * Send [payload] to every target in [targets], in parallel.
     * Returns one [SendResult] per target, in the same order as the input list.
     *
     * Each target gets its own coroutine so a slow DNS lookup on one host doesn't
     * block the others. All coroutines run on Dispatchers.IO.
     *
     * Marked `open` to allow unit tests to substitute a fake sender.
     */
    open suspend fun sendToAll(payload: ByteArray, targets: List<ServerTarget>): List<SendResult> =
        coroutineScope {
            if (targets.isEmpty()) return@coroutineScope emptyList()
            targets.map { target ->
                async(Dispatchers.IO) { sendSync(payload, target) }
            }.awaitAll()
        }

    /** Send to a single target. Synchronous; callers should be on Dispatchers.IO. */
    private fun sendSync(payload: ByteArray, target: ServerTarget): SendResult {
        return try {
            val addr = InetAddress.getByName(target.host)
            val packet = DatagramPacket(payload, payload.size, addr, target.port)
            ensureSocket().send(packet)
            SendResult(target = target, bytesSent = payload.size)
        } catch (t: Throwable) {
            Log.w(TAG, "send failed: ${target.host}:${target.port} — ${t.message}")
            SendResult(target = target, bytesSent = 0, error = t)
        }
    }

    /**
     * Send to a single target as a suspending operation (used by the "test send" button
     * in settings). For batch sends from the transmission engine, use [sendToAll].
     */
    suspend fun send(payload: ByteArray, target: ServerTarget): SendResult =
        withContext(Dispatchers.IO) { sendSync(payload, target) }

    /** Release the underlying socket. Safe to call multiple times. */
    @Synchronized
    fun close() {
        socket?.close()
        socket = null
    }

    companion object {
        private const val TAG = "UdpSender"
    }
}
