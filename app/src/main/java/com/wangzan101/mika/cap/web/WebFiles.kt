package com.wangzan101.mika.cap.web

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * A task's files on the web: downloads land in downloads/ under [workspaceDir] (this conversation's workspace), and uploads only hand over files from this workspace.
 * Uploads never hand over the workspace files themselves: the gate copies them into the app-private staging area ([UploadStaging]), and FileProvider only exposes the staging area.
 * After the picker takes a copy, the copy is kept for [keepMs] (read only when the page submits the form) and deleted afterwards or when the task ends; copies never taken are deleted immediately.
 */
class WebFiles(
    private val context: Context,
    val workspaceDir: File,
    val downloads: WebDownloads,
    gate: WorkspaceFileGate,
    private val scope: CoroutineScope,
    private val keepMs: Long = 10 * 60_000L,
) {
    private val staging = UploadStaging(File(context.cacheDir, UploadStaging.DIR), gate)
    private val live = mutableMapOf<UploadStaging.Staged, Job?>()

    sealed interface Upload {
        class Ok(val uris: List<Uri>, val names: List<String>, val staged: UploadStaging.Staged) : Upload
        /** [error] is file_not_found, path_outside_workspace, linked_file, or file_too_large. */
        class Refused(val error: String, val path: String) : Upload
    }

    fun prepareUpload(paths: List<String>): Upload {
        val staged = when (val r = staging.stage(workspaceDir, paths)) {
            is UploadStaging.Result.Refused -> return Upload.Refused(r.error, r.path)
            is UploadStaging.Result.Ok -> r.staged
        }
        val uris = try {
            staged.files.map { FileProvider.getUriForFile(context, "${context.packageName}.webfiles", it) }
        } catch (e: IllegalArgumentException) {
            staging.delete(staged)
            return Upload.Refused("file_not_found", paths.first())
        }
        synchronized(live) { live[staged] = null }
        return Upload.Ok(uris, staged.files.map { it.name }, staged)
    }

    /** When the upload is released: if the picker took it ([consumed]), delete it after [keepMs]; if not, delete it immediately. */
    fun release(staged: UploadStaging.Staged, consumed: Boolean) {
        if (!consumed) return drop(staged)
        val job = scope.launch {
            delay(keepMs)
            drop(staged)
        }
        synchronized(live) { if (staged in live) live[staged] = job else job.cancel() }
    }

    private fun drop(staged: UploadStaging.Staged) {
        synchronized(live) { live.remove(staged) }?.cancel()
        staging.delete(staged)
    }

    /** Task ends: delete all copies still kept. */
    fun close() {
        val all = synchronized(live) { live.keys.toList() }
        all.forEach(::drop)
    }

    companion object {
        /** Clear copies left behind by the previous process on startup. */
        fun clearStale(context: Context, gate: WorkspaceFileGate) {
            UploadStaging(File(context.cacheDir, UploadStaging.DIR), gate).clear()
        }
    }
}
