package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.capability.PromptSections
import com.wangzan101.mika.core.prompt.PersonaPrompt
import com.wangzan101.mika.core.prompt.Prompts

/**
 * System prompt: P0 identity (prompts/system/identity.md), P1 general principles (system/principles.md, with no tool
 * names), then the general rules for general tools (capabilities/general.md, general tools are always present), then
 * the sections provided by the core itself and by each capability.
 * A section appears only when that group of tools is in the tool list sent to the model for this task: the
 * instructions and the tools come from the same check.
 * Assembled once at task start and unchanged by a single character within the task.
 */
object SystemPrompt {
    /**
     * [assistant] is the assistant's self-name (the "appellation" in the persona), Claire by default; before entering
     * the prompt it is sanitized here by [PersonaPrompt.nickname].
     * When it is not Claire, the identity sentence uses it, immediately followed by an explanation: Claire is the name
     * of the App it lives in, and Claire in later text and tool descriptions refers to this App and to itself.
     * When it is Claire, the output is byte-for-byte identical to before this parameter existed.
     */
    fun identity(assistant: String = PersonaPrompt.DEFAULT_NICKNAME): String {
        // The name lands outside the fence, so it is sanitized again here rather than trusting the caller to have done it
        val name = PersonaPrompt.nickname(assistant)
        return listOfNotNull(
            Prompts.fill(Prompts.system("identity"), mapOf("assistant" to name)),
            if (name == PersonaPrompt.DEFAULT_NICKNAME) null else Prompts.fill(Prompts.read("persona/renamed.md"), mapOf("assistant" to name)),
            Prompts.system("principles"),
            read(GENERAL),
        ).joinToString("\n\n")
    }

    /** Appends the persona layer after identity, principles, and general rules; returns as-is when there is no persona. */
    fun withPersona(base: String, layer: String?): String = if (layer == null) base else "$base\n\n$layer"

    /**
     * Generates the language-directive section from the language preference.
     * When a specific language is chosen (such as English or Chinese), instructs the model to think, write step notes,
     * state tool intents, and give the final reply all in that language.
     * When empty or system-adaptive, instructs the model to strictly follow the language of the user's prompt.
     */
    fun languageDirective(languageTag: String?): String {
        val tag = languageTag?.trim()?.lowercase()
        return when {
            tag == null || tag.isEmpty() || tag == "system" ->
                "## Communication & Reasoning Language\nAlways mirror the language of the user's prompt for all internal thinking, progress notes, tool call reasons, and final replies."
            tag.startsWith("en") ->
                "## Communication & Reasoning Language\nYou must think, formulate all progress notes, tool call reasons, and final replies in English."
            tag.startsWith("zh") ->
                "## 语言设定\n所有思考过程、动作用意、执行进度与最终回复均使用中文。"
            tag.startsWith("ja") ->
                "## 言語設定\nすべての思考、進捗メモ、ツールの理由、および回答は日本語で行ってください。"
            tag.startsWith("es") ->
                "## Directiva de idioma\nDebes pensar, formular todas las notas de progreso, motivos de llamadas a herramientas y respuestas finales en español."
            tag.startsWith("ko") ->
                "## 언어 설정\n모든 생각, 진행 상황 메모, 도구 호출 이유 및 최종 답변은 한국어로 작성해야 합니다."
            tag.startsWith("ar") ->
                "## توجيه اللغة\nيجب عليك التفكير وكتابة جميع ملاحظات التقدم وأسباب استخدام الأدوات والردود النهائية باللغة العربية."
            tag.startsWith("fa") ->
                "## راهنمای زبان\nباید تمام افکار، یادداشت‌های پیشرفت، دلایل فراخوانی ابزارها و پاسخ‌های نهایی را به فارسی بنویسید."
            tag.startsWith("pt") ->
                "## Diretriz de idioma\nVocê deve pensar, formular todas as notas de progresso, motivos de chamada de ferramentas e respostas finais em português."
            tag.startsWith("id") || tag.startsWith("in") ->
                "## Arahan Bahasa\nAnda harus berpikir, menulis semua catatan kemajuan, alasan pemanggilan alat, dan balasan akhir dalam bahasa Indonesia."
            tag.startsWith("ru") ->
                "## Языковая директива\nВы должны думать, формулировать все заметки о ходе выполнения, причины вызова инструментов и окончательные ответы на русском языке."
            tag.startsWith("tr") ->
                "## Dil Yönergesi\nTüm düşünceleri, ilerleme notlarını, araç çağrı nedenlerini ve nihai yanıtları Türkçe olarak oluşturmalısınız."
            else ->
                "## Communication & Reasoning Language\nAlways mirror the language of the user's prompt for all internal thinking, progress notes, tool call reasons, and final replies."
        }
    }

    /** Appends the language-directive layer after the base prompt. */
    fun withLanguage(base: String, languageTag: String?): String {
        val directive = languageDirective(languageTag)
        return if (directive.isBlank()) base else "$base\n\n$directive"
    }

    fun assemble(identity: String, sections: List<String>): String = (listOf(identity) + sections).joinToString("\n\n")

    private const val GENERAL = "general"

    /** One capability section. */
    internal fun read(name: String): String = Prompts.capability(name)
}

/**
 * The core's own sections, placed before the capability sections: which entry point leads into the groups expanded on
 * demand (groups, listing only the entries offered for this task; once expanded they stay, so the system prompt
 * changes little); how to choose when there are two or more screens, and how to work across two screens (channels);
 * handing over to the user after expanding a screen, and checking before placing an order or sending (handover); how to
 * read an action result that carries its own screen (inline-screen); multiple steps at once (batch).
 */
internal object CoreSections : PromptSections {
    override fun sections(env: PromptEnv): List<String> = listOfNotNull(
        env.gate(SystemPrompt.read("groups")).takeIf { it.lines().size > 1 },
        SystemPrompt.read("channels").takeIf { env.screens >= 2 },
        SystemPrompt.read("handover").takeIf { env.openScreens >= 1 },
        SystemPrompt.read("inline-screen").takeIf { env.inlineScreen && env.openScreens >= 1 },
        SystemPrompt.read("batch").takeIf { DO_STEPS_TOOL in env.offered },
    ).map(env::gate)
}
