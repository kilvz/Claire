package com.wangzan101.mika.takeover

import android.app.Activity
import android.os.Bundle
import com.wangzan101.mika.task.TaskService

/** Shows no UI: forwards the takeover button tapped in the notification to the task service and finishes immediately. */
class TakeoverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent?.getIntExtra(TaskService.EXTRA_WAIT_KEY, -1)?.takeIf { it >= 0 }
        // Which task the button belongs to; when several tasks are waiting for takeover at once it must not land on another task
        val run = intent?.getStringExtra(TaskService.EXTRA_RUN)
        when (intent?.action) {
            TaskService.ACTION_TAKE_OVER -> TaskService.takeOver(this, key, run)
            TaskService.ACTION_HAND_BACK -> TaskService.handBack(this, key, run)
            TaskService.ACTION_FINISHED_MYSELF -> TaskService.finishedMyself(this, key, run)
            TaskService.ACTION_SKIP -> TaskService.skip(this, key, run)
        }
        finish()
    }
}
