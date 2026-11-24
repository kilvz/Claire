package com.wangzan101.mika.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionService

/**
 * Keeps a public service connection alive during recognition so the recognition service can use the caller's foreground capabilities.
 * This only waits for the connection notification and never calls the service's Binder protocol; recognition itself still goes through SpeechRecognizer.
 */
internal class SpeechServiceLease(
    private val binder: Binder,
    private val onReady: () -> Unit,
    private val onUnavailable: () -> Unit,
) : AutoCloseable {
    /** Thin interface over the platform binding; the lifecycle can be tested without depending on system objects. */
    interface Binder {
        fun bind(onConnected: () -> Unit, onUnavailable: () -> Unit): Boolean
        fun unbind()
    }

    private val lock = Any()
    private var started = false
    private var binding = false
    private var accepted = false
    private var closed = false
    private var unbound = false
    private var ready = false
    private var pendingReady = false
    private var pendingUnavailable = false

    /**
     * Each connection is started only once. false means nothing was bound; the caller then uses the ordinary recognition path and no failure notification is sent from here.
     * Callbacks that arrive synchronously inside bind wait until it returns successfully before being handed to the caller, so a failed binding does not start recognition first.
     */
    fun start(): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (started) return binding || accepted
            started = true
            binding = true
        }
        val bound = try {
            binder.bind(::connected, ::unavailable)
        } catch (_: Exception) {
            false
        }
        val cleanup = synchronized(lock) {
            binding = false
            accepted = bound
            if (!bound) closed = true
            closed && takeUnbind()
        }
        if (cleanup) unbindQuietly()
        if (bound) {
            val failed = synchronized(lock) { pendingUnavailable }
            if (failed) unavailable() else {
                val notifyReady = synchronized(lock) { pendingReady }
                if (notifyReady) connected()
            }
        }
        return bound
    }

    private fun connected() = synchronized(lock) {
        if (closed || pendingUnavailable || ready) return@synchronized
        if (binding) {
            pendingReady = true
            return@synchronized
        }
        ready = true
        // Callbacks and close are serialized; the caller may call close from inside a callback, and re-entry is handled.
        try {
            onReady()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun unavailable() {
        val cleanup = synchronized(lock) {
            if (closed) return
            if (binding) {
                pendingUnavailable = true
                return
            }
            closed = true
            takeUnbind()
        }
        if (cleanup) unbindQuietly()
        onUnavailable()
    }

    override fun close() {
        val cleanup = synchronized(lock) {
            if (closed) return
            closed = true
            // Do not unbind early while bind has not returned yet: the connection may not be registered yet. start does it once bind returns.
            takeUnbind()
        }
        if (cleanup) unbindQuietly()
    }

    /** Called only while holding the lock; once a binding has been started, the connection registration is cleared even when it returned false or threw. */
    private fun takeUnbind(): Boolean {
        if (!started || binding || unbound) return false
        unbound = true
        return true
    }

    private fun unbindQuietly() {
        try {
            binder.unbind()
        } catch (_: Exception) {
            // A registration that never succeeded or a service that already disappeared also counts as cleaned up; no repeated unbind.
        }
    }

    companion object {
        /** Holds a connection only to a public service the caller already has the right to bind; requests no permission and changes no system configuration. */
        fun forService(
            context: Context,
            service: ComponentName,
            onReady: () -> Unit,
            onUnavailable: () -> Unit,
        ): SpeechServiceLease? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return try {
                val app = context.applicationContext
                val info = app.packageManager.getServiceInfo(service, 0)
                if (!info.exported) return null
                val permission = info.permission
                if (!permission.isNullOrEmpty() && app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return null
                SpeechServiceLease(ServiceBinder(app, service), onReady, onUnavailable)
            } catch (_: Exception) {
                null
            }
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private class ServiceBinder(private val context: Context, private val service: ComponentName) : Binder {
        private var connected: (() -> Unit)? = null
        private var unavailable: (() -> Unit)? = null
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { connected?.invoke() }
            override fun onServiceDisconnected(name: ComponentName?) { unavailable?.invoke() }
            override fun onNullBinding(name: ComponentName?) { unavailable?.invoke() }
            override fun onBindingDied(name: ComponentName?) { unavailable?.invoke() }
        }

        override fun bind(onConnected: () -> Unit, onUnavailable: () -> Unit): Boolean {
            connected = onConnected
            unavailable = onUnavailable
            return context.bindService(Intent(RecognitionService.SERVICE_INTERFACE).setComponent(service), connection,
                Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES)
        }

        override fun unbind() {
            // Even if the system's unbind throws, late callbacks must not hold the session any more.
            connected = null
            unavailable = null
            context.unbindService(connection)
        }
    }
}
