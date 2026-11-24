package com.wangzan101.mika.cap.web.cdp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.wangzan101.mika.core.cap.web.cdp.CdpStream
import com.wangzan101.mika.core.cap.web.cdp.CdpTransport
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Two ways to connect to this process's WebView debugging socket. The endpoint is the abstract socket name.
 * Read and write deadlines are handled by the protocol layer (closing the stream at the deadline makes blocking reads and writes return), so no socket timeout is set here.
 */
internal class DirectTransport : CdpTransport {
    override fun open(endpoint: String): CdpStream {
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress(endpoint, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        return object : CdpStream {
            override val input: InputStream = s.inputStream
            override val output: OutputStream = s.outputStream
            override fun close() {
                runCatching { s.shutdownInput() }
                runCatching { s.shutdownOutput() }
                s.close()
            }
        }
    }
}

/** Connecting on someone else's behalf (the built-in helper, under the shell identity): [relay] connects and hands the socket back; it throws IOException when the relaying side is absent. */
internal class RelayTransport(
    private val relay: (String) -> ParcelFileDescriptor?,
) : CdpTransport {
    override fun open(endpoint: String): CdpStream {
        val pfd = try {
            relay(endpoint)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("phone helper could not relay: ${e.message}", e)
        } ?: throw IOException("phone helper refused or could not connect $endpoint")
        return FdStream(pfd)
    }
}

/** The handed-back socket fd. On close, shut it down first so a thread blocked on read returns. */
private class FdStream(private val pfd: ParcelFileDescriptor) : CdpStream {
    override val input: InputStream = FileInputStream(pfd.fileDescriptor)
    override val output: OutputStream = FileOutputStream(pfd.fileDescriptor)

    override fun close() {
        runCatching { Os.shutdown(pfd.fileDescriptor, OsConstants.SHUT_RDWR) }
        pfd.close()
    }
}
