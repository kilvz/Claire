package com.wangzan101.mika.sandbox

import com.wangzan101.mika.cap.sandbox.SandboxForeground

/** Entry point of the foreground service that runs while the runtime environment is being prepared: the manifest registers this fully-qualified name; the implementation lives in the sandbox capability. */
class SandboxService : SandboxForeground()
