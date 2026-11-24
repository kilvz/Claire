package com.wangzan101.mika.task

import androidx.test.platform.app.InstrumentationRegistry

/** For device tests: the single task environment inside the app process under test (swap model factories, evaluation hooks, look at screen frames). */
internal val testTasks: TaskEnvironment
    get() = TaskEnvironment.of(InstrumentationRegistry.getInstrumentation().targetContext)
