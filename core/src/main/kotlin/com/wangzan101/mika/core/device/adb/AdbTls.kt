package com.wangzan101.mika.core.device.adb

import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** TLS keying material export (RFC 8446 §7.5). The JDK has no public interface for it, so the platform provides one: on Android it is `SSLSockets.exportKeyingMaterial`. */
fun interface KeyingMaterialExporter {
    fun export(socket: SSLSocket, label: String, length: Int): ByteArray
}

/**
 * The TLS 1.3 that adb uses: both sides present self-signed certificates and neither validates the other's certificate chain.
 * During pairing the identity is bound to the exported key by SPAKE2; when connecting, the device compares the public key in our certificate. Only this device's own addresses are connected to.
 */
class AdbTls(
    val identity: AdbIdentity,
    private val exporter: KeyingMaterialExporter,
    private val provider: Provider? = null,
) {
    val context: SSLContext by lazy {
        val ctx = if (provider == null) SSLContext.getInstance("TLS") else SSLContext.getInstance("TLS", provider)
        ctx.init(arrayOf(FixedKeyManager(identity.keyPair.private, identity.certificate)), arrayOf(TrustAll), null)
        ctx
    }

    /** Opens a new TCP connection to the caller-verified [target] and completes the TLS handshake; the host name is not resolved again. */
    fun connect(target: InetSocketAddress, timeoutMs: Int): SSLSocket {
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.connect(target, timeoutMs)
            plain.soTimeout = timeoutMs
        } catch (e: Exception) {
            plain.close()
            throw e
        }
        return wrap(plain, target, client = true)
    }

    /** Performs the TLS handshake over an existing connection; [client] = false is only for the fake server in tests. */
    fun wrap(plain: Socket, target: InetSocketAddress, client: Boolean): SSLSocket {
        val host = target.address?.hostAddress ?: throw IllegalArgumentException("ADB address must be resolved")
        val s = context.socketFactory.createSocket(plain, host, target.port, true) as SSLSocket
        try {
            s.enabledProtocols = arrayOf("TLSv1.3")
            s.useClientMode = client
            if (!client) s.needClientAuth = true
            s.startHandshake()
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return s
    }

    fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray = exporter.export(socket, label, length)

    private class FixedKeyManager(private val key: PrivateKey, private val cert: X509Certificate) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(cert)
        override fun getPrivateKey(alias: String?) = key
    }

    private object TrustAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val ALIAS = "adb"
    }
}
