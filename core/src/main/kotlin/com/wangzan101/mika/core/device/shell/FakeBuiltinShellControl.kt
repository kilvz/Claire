package com.wangzan101.mika.core.device.shell

import kotlinx.coroutines.flow.MutableStateFlow

/** For tests and for when the real implementation is not wired up yet: records calls, and the caller sets the status. [pairingResult] is the status after [startPairing]. */
class FakeBuiltinShellControl(
    initial: BuiltinShellStatus = BuiltinShellStatus.NotPaired,
    var pairingResult: BuiltinShellStatus = BuiltinShellStatus.Running,
    var retryResult: BuiltinShellStatus = BuiltinShellStatus.Running,
) : BuiltinShellControl {
    override val status = MutableStateFlow(initial)
    override val pairingDiscovery = MutableStateFlow<PairingDiscovery>(PairingDiscovery.Idle)
    val calls = mutableListOf<String>()
    override var shizukuStart: Boolean = false
    override var transport: ShellTransport = ShellTransport.Auto

    override fun selectTransport(transport: ShellTransport) {
        calls += "transport=$transport"
        this.transport = transport
    }

    override fun startDiscovery() {
        calls += "discover"
        if (pairingDiscovery.value == PairingDiscovery.Idle) pairingDiscovery.value = PairingDiscovery.Searching
    }

    override fun stopDiscovery() {
        calls += "stopDiscovery"
        pairingDiscovery.value = PairingDiscovery.Idle
    }

    override suspend fun startPairing(code: String, port: Int?) {
        calls += "pair $code" + (port?.let { " $it" } ?: "")
        status.value = BuiltinShellStatus.Connecting
        status.value = pairingResult
    }

    override suspend fun retry() {
        calls += "retry"
        status.value = BuiltinShellStatus.Connecting
        status.value = retryResult
    }

    /** 测试里直接拨这个标记,再调 [pauseChanged],模拟持久化的暂停标记变了。 */
    var pausedFlag = false

    override val paused: Boolean get() = pausedFlag

    override fun pauseChanged() {
        calls += if (pausedFlag) "pause" else "resume"
        status.value = if (pausedFlag) BuiltinShellStatus.Paused else retryResult
    }

    override suspend fun unpair() {
        calls += "unpair"
        status.value = BuiltinShellStatus.NotPaired
    }
}
