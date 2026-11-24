package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.safety.gate.gateTargetOf
import com.wangzan101.mika.core.safety.gate.GateTarget
import com.wangzan101.mika.core.calls.PhoneCallPort
import com.wangzan101.mika.core.calls.PhoneCallTask
import com.wangzan101.mika.core.model.Part
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import com.wangzan101.mika.core.testing.WEB_SCRIPT_TOOL
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.assembly.allTools
import com.wangzan101.mika.core.cap.audio.AudioPort
import com.wangzan101.mika.core.cap.image.ImagePort
import com.wangzan101.mika.core.cap.image.SavedImage
import com.wangzan101.mika.core.cap.image.StoredImage
import com.wangzan101.mika.core.cap.transcribe.TranscribePort
import com.wangzan101.mika.core.cap.transcribe.Transcript
import com.wangzan101.mika.core.model.GeneratedImage
import com.wangzan101.mika.core.model.ImageCapabilities
import com.wangzan101.mika.core.model.ImageReference
import com.wangzan101.mika.core.model.ImageRequest
import com.wangzan101.mika.core.model.ImageResponse
import com.wangzan101.mika.core.model.SavedAudio
import com.wangzan101.mika.core.model.StoredAudio
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.core.testing.SANDBOX_TOOLS
import com.wangzan101.mika.core.testing.WEB_TOOLS

/** The classification derived from each tool's declared risk matches the name-based table from before the switch; the phone and web screen tool sets each carry the same verbs. */
class ToolRiskTest {
    private fun both(vararg verbs: String) = verbs.flatMap { listOf("phone_$it", "web_$it") }.toSet()

    /** The table of tools that used to pass the lock-screen/pause gate. */
    private val oldWriting = both("tap", "scroll", "swipe", "type_text", "clear_text", "back", "press_key", "diagnose") + setOf(
        "phone_launch_app", "phone_share_text", "phone_share_audio", "phone_share_image", "phone_gesture", "phone_sms_login", "hand_to_user",
        "phone_set_alarm", "phone_set_timer", "phone_show_alarms", "phone_add_calendar_event", "phone_set_wheel",
        "web_navigate", "web_tabs", "web_upload", "web_interact", "web_site_mode", "phone_open_link", "web_keep_open", "phone_app_after_task",
    )
    private val oldSandbox = setOf("run_command", "run_bundled_script", "read_file", "write_file", "search_files", "share_file", "view_image", "list_folder_files")
    private val oldWeb = setOf(
        "web_navigate", "web_read_page", "web_evaluate_js", "web_run_site_command", "web_tabs", "web_downloads", "web_upload",
        "web_site_mode", "web_script", "web_interact", "web_capture", "web_wait_for", "web_keep_open",
    ) + both("observe", "list_elements", "screenshot", "describe", "find_visually", "tap", "scroll", "swipe",
        "type_text", "clear_text", "back", "press_key", "diagnose").filter { it.startsWith("web_") }
    private val names = allTools().map { it.name }

    @Test fun writingToolsDerivedFromRisk() {
        assertEquals(oldWriting, names.filter { isWritingTool(it, JsonObject(emptyMap())) }.toSet())
    }

    @Test fun groupsMatchOldNameSets() {
        assertEquals(oldSandbox, names.filter(::isSandboxTool).toSet())
        assertEquals(oldSandbox, SANDBOX_TOOLS)
        assertEquals(oldWeb, WEB_TOOLS)
    }

    private object NoTranscribePort : TranscribePort {
        override suspend fun transcribe(path: String, language: String?) = Transcript("")
    }

    private object NoAudioPort : AudioPort {
        override val modelName = "test-voice"
        override val capabilities = TtsCapabilities(maxChars = 100, formats = setOf("wav"))
        override suspend fun synthesize(request: TtsRequest) = TtsResponse(ByteArray(0), "audio/wav")
        override suspend fun store(response: TtsResponse, nameHint: String?, text: String) = StoredAudio("/workspace/out/audio/x.wav", 0, "audio/wav")
        override suspend fun saveToMusic(path: String, name: String?) = SavedAudio("Music/Claire/x.wav", "content://x", 0)
        override suspend fun speak(request: TtsRequest) {}
    }

    private object NoImagePort : ImagePort {
        override val modelName = "test-image"
        override val capabilities = ImageCapabilities(supportsEdit = true, maxReferences = 1, maxCount = 1)
        override suspend fun generate(request: ImageRequest) = ImageResponse(emptyList())
        override suspend fun readReference(path: String) = ImageReference(ByteArray(0), "image/png")
        override suspend fun store(image: GeneratedImage, nameHint: String?, prompt: String) = StoredImage("/workspace/out/images/x.png", 0, 0, 0)
        override suspend fun saveToGallery(path: String, name: String?) = SavedImage("Pictures/Claire/x.png", "content://x", 0)
    }

    @Test fun readCallsRefuseWhatTheOldListsRefused() = runTest {
        val refused = oldWriting + oldSandbox + WEB_SCRIPT_TOOL +
            setOf("phone_find", "phone_collect", "phone_dialog", "phone_run", "do_each", "phone_system_command", "phone_calendar_bulk_insert", "schedule_task", "spawn_agent", "settings_set", "root_run_command", "web_search",
                "mika_open_screen", "mika_open_file", "mika_notify", "mika_copy",
                "mika_open_in_browser", "mika_search_in_browser", "mika_login_site", "mika_confirm_login", "mika_favorites",
                "phone_call_state", "phone_call_dial", "phone_call_answer", "phone_call_hangup", "phone_call_takeover",
                "list_device_files", "copy_device_file", "search_device_files", "list_device_directory_hints", "update_device_directory_description",
                "audio_generate", "audio_save", "audio_transcribe", "speak",
                "image_generate", "image_edit", "image_save", "wechat_send_owner")
        assertEquals(refused, allTools().filter { !it.readOnly }.map { it.name }.toSet())
        // Root command and call tools are registered only when a port is supplied: provide one of each and every tool is present
        val d = toolDispatcher(FakeScreen(), audio = NoAudioPort, audioSaveEnabled = true, audioSpeakTool = true, transcribe = NoTranscribePort,
            image = NoImagePort, imageSaveEnabled = true, root = object : com.wangzan101.mika.core.cap.root.RootPort {
            override suspend fun run(command: String, timeoutMs: Long) = com.wangzan101.mika.core.cap.root.RootRun.Unavailable
        }, deviceFiles = object : com.wangzan101.mika.core.cap.devicefiles.DeviceFilesPort {
            override suspend fun search(query: com.wangzan101.mika.core.workspace.DeviceFileSearchQuery): com.wangzan101.mika.core.workspace.DeviceFileSearchResult = error("read call reached device files")
            override suspend fun list(path: String?, cursor: String?, limit: Int): com.wangzan101.mika.core.cap.devicefiles.DeviceFilePage = error("read call reached device files")
            override suspend fun importFile(path: String, workspace: String): com.wangzan101.mika.core.workspace.DeviceFileImport = error("read call reached device files")
        }, phoneCalls = object : PhoneCallPort {
            override suspend fun state() = JsonObject(emptyMap())
            override suspend fun dial(number: String, simId: String?, task: PhoneCallTask?, ownerConversationId: String?) = JsonObject(emptyMap())
            override suspend fun answer(callId: String) = JsonObject(emptyMap())
            override suspend fun hangup(callId: String) = JsonObject(emptyMap())
            override suspend fun takeover() = JsonObject(emptyMap())
        }, wechatOwner = com.wangzan101.mika.core.cap.wechat.WechatOwnerPort {
            com.wangzan101.mika.core.cap.wechat.WechatOwnerResult(com.wangzan101.mika.core.cap.wechat.WechatOwnerResult.Status.Unavailable)
        })
        for (name in names) {
            val r = d.executeRead(Part.ToolCall("c", name, "{}")).content
            assertEquals(name, name in refused, r["error"] == JsonPrimitive("not_a_read"))
        }
    }

    @Test fun externalToolsDeclareHowTheirTargetIsJudged() {
        for (t in allTools()) assertEquals(t.name, t.risk == Risk.External, t.gateTarget != null || t.approvalRisk != null)
        assertEquals(both("tap", "type_text", "press_key", "swipe", "scroll") + setOf("web_interact", "web_upload", "schedule_task",
            "mika_open_in_browser", "mika_search_in_browser", "mika_login_site"),
            allTools().filter { it.risk == Risk.External }.map { it.name }.toSet())
    }

    /**
     * The branches that needsCheck / classify used to split by name are now split by the declared target; the judgment in each branch is unchanged,
     * and the name-to-target mapping is the same as before, so the verdicts are the same (specific situations are covered by IrreversibleDetectorTest).
     */
    @Test fun gateTargetsMatchOldNameBranches() {
        val perVerb = mapOf("tap" to GateTarget.Tap, "type_text" to GateTarget.Enter, "press_key" to GateTarget.Key, "swipe" to GateTarget.Swipe, "scroll" to GateTarget.Swipe)
        val old = perVerb.flatMap { (v, g) -> listOf("phone_$v" to g, "web_$v" to g) }.toMap() +
            mapOf("web_upload" to GateTarget.FileControl, "web_interact" to GateTarget.Interact)
        for (t in allTools().map { it.name } + "do_steps" + "unknown") assertEquals(t, old[t], gateTargetOf(t))
    }

    @Test fun everyToolHasAUniqueKnownName() {
        assertEquals(names.size, names.toSet().size)
    }
}
