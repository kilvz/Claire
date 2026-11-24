package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.NioFdSys
import com.wangzan101.mika.core.workspace.WorkspaceFileGate

/** The gate in JVM tests (a path-based approximation; on the phone it is AndroidFdSys.gate). */
internal val JVM_GATE = WorkspaceFileGate(NioFdSys)
