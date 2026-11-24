package com.wangzan101.mika.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.cap.sandbox.SandboxPathContract
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import org.junit.runner.RunWith
import java.io.File

/** Runs the path-rule contract on a real device's filesystem (symlinks and canonicalFile behave as Android defines them). */
@RunWith(AndroidJUnit4::class)
class AndroidSandboxPathContractDeviceTest : SandboxPathContract() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    override fun sandbox(): SandboxPort = AndroidSandbox(context, SandboxState(context))
    override fun outsideDir(): File = File(context.filesDir, "contract-outside").apply { mkdirs() }
}
