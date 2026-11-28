package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.core.tool.ToolIndex

/**
 * How tools are named in the UI: the label declared by a tool is swapped for the corresponding resource based on the UI language; tools without a label show the tool name as-is.
 * Old names in run records from before the rename are resolved to the current tool via [legacy].
 */
class ToolLabels(private val tools: ToolIndex, private val legacy: LegacyNames = legacyNames()) {
    fun label(tool: String): String {
        // Every MCP tool name embeds a connector-specific digest; the user only needs to know a connector was used (the activity row names it), never the internal name.
        if (tool.startsWith("mcp_")) return tr(R.string.tool_mcp)
        // Tools that declare no label fall back to looking up the table by tool name (still show the tool name if not found); declared ones are looked up by declared text as before.
        val declared = (tools[tool] ?: legacy.resolve(tool)?.let { tools[it] })?.label ?: tool
        return TOOL_LABELS[declared]?.let { tr(it) } ?: declared
    }

    companion object {
        /** Declared tool label → UI text; ToolLabelsTest verifies every tool has an entry. */
        val TOOL_LABELS: Map<String, Int> = mapOf(
            "Sign in with SMS code" to R.string.sms_login_tool,
            "List installed apps" to R.string.tool_list_apps,
            "Open app" to R.string.tool_open_app,
            "Share text to app" to R.string.tool_share_text,
            "Share audio to app" to R.string.tool_share_audio,
            "Share image to app" to R.string.tool_share_image,
            "Generate speech" to R.string.tool_audio_generate,
            "Save audio" to R.string.tool_audio_save,
            "Transcribe audio" to R.string.tool_audio_transcribe,
            "Speak" to R.string.tool_speak,
            "Share video to app" to R.string.tool_share_video,
            "Generate video" to R.string.tool_video_generate,
            "View video" to R.string.tool_video_frames,
            "View video info" to R.string.tool_video_info,
            "Save video" to R.string.tool_video_save,
            "Set alarm" to R.string.tool_set_alarm,
            "Set timer" to R.string.tool_set_timer,
            "View alarms" to R.string.tool_show_alarms,
            "Add calendar event" to R.string.tool_add_calendar_event,
            "Add calendar events in bulk" to R.string.tool_calendar_bulk_insert,
            "Turn wheel" to R.string.tool_set_wheel,
            "Scroll to find" to R.string.tool_scroll_find,
            "Wait for screen condition" to R.string.tool_wait_condition,
            "Wait for screen event" to R.string.tool_wait_event,
            "Extract screen data" to R.string.tool_extract,
            "Check dialog" to R.string.tool_check_dialog,
            "Multi-point gesture" to R.string.tool_gesture,
            "System command" to R.string.tool_shell,
            "Root command" to R.string.tool_root,
            "Call status" to R.string.calls_tool_state,
            "Make a call" to R.string.calls_tool_dial,
            "AI answer" to R.string.calls_answer,
            "Hang up" to R.string.calls_tool_hangup,
            "Take over" to R.string.calls_takeover,
            "Find screen control" to R.string.tool_find_element,
            "Collect screens" to R.string.tool_collect,
            "Operate the phone continuously" to R.string.tool_phone_run,
            "Browse device files" to R.string.tool_list_device_files,
            "Search device files" to R.string.tool_search_device_files,
            "Copy device file" to R.string.tool_copy_device_file,
            "Process files" to R.string.tool_process_files,
            "Run bundled script" to R.string.tool_run_script,
            "Read file" to R.string.tool_read_file,
            "View image" to R.string.tool_view_image,
            "Write file" to R.string.tool_write_file,
            "Search files" to R.string.tool_search_files,
            "Export file" to R.string.tool_export_file,
            "Browse allowed folders" to R.string.tool_list_folder_files,
            "Generate image" to R.string.tool_image_generate,
            "Edit image" to R.string.tool_image_edit,
            "Save image" to R.string.tool_image_save,
            "Open web page" to R.string.tool_open_page,
            "Search the web" to R.string.web_search_action,
            "Switch tab" to R.string.tool_switch_tab,
            "Switch page version" to R.string.tool_switch_version,
            "Keep page open" to R.string.tool_keep_page,
            "Choose what happens to the app" to R.string.tool_app_after_task,
            "Read web page" to R.string.tool_read_page,
            "View downloads" to R.string.tool_downloads,
            "View page data" to R.string.tool_network,
            "Wait for page change" to R.string.tool_wait_page,
            "Read page data" to R.string.tool_read_script,
            "Run site command" to R.string.tool_site_command,
            "Upload file" to R.string.tool_upload,
            "Hit a roadblock" to R.string.tool_obstacle,
            "Find app/site knowledge" to R.string.tool_find_knowledge,
            "Find skill" to R.string.tool_find_skill,
            "View app/site knowledge" to R.string.tool_read_knowledge,
            "View skill" to R.string.tool_read_skill,
            "Save skill" to R.string.tool_save_skill,
            "Search memory" to R.string.tool_memory_search,
            "Save to memory" to R.string.tool_memory_save,
            "Edit memory" to R.string.tool_memory_edit,
            "View settings" to R.string.tool_list_settings,
            "Change settings" to R.string.tool_set_setting,
            "View status" to R.string.tool_mika_status,
            "Open Claire's screens" to R.string.tool_mika_open_screen,
            "Open file" to R.string.tool_mika_open_file,
            "Send notification" to R.string.tool_mika_notify,
            "Copy text" to R.string.tool_mika_copy,
            "Open in browser" to R.string.tool_mika_open_in_browser,
            "Search in browser" to R.string.tool_mika_search_in_browser,
            "View site sign-ins" to R.string.tool_mika_sites,
            "View current page" to R.string.tool_mika_user_page,
            "Sign in to a site" to R.string.tool_mika_login_site,
            "Confirm sign-in" to R.string.tool_mika_confirm_login,
            "Favorite sites" to R.string.tool_mika_favorites,
            "Explain why it failed" to R.string.tool_explain_failure,
            "Ask the user" to R.string.tool_ask_user,
            "Hand off to the user" to R.string.tool_takeover,
            "Ask for approval" to R.string.tool_confirm,
            "Wait" to R.string.tool_wait,
            "Inspect screen" to R.string.tool_diagnose,
            "Back" to R.string.tool_back,
            "Press key" to R.string.tool_press_key,
            "View screen" to R.string.tool_observe,
            "Read UI elements" to R.string.tool_list_elements,
            "Screenshot" to R.string.tool_screenshot,
            "Scroll" to R.string.tool_scroll,
            "Swipe" to R.string.tool_swipe,
            "Tap" to R.string.tool_tap,
            "Type text" to R.string.tool_type,
            "Clear input" to R.string.tool_clear,
            "wechat_send_owner" to R.string.tool_wechat_owner,
        )
    }
}
