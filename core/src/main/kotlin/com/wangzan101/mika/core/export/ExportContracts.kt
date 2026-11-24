package com.wangzan101.mika.core.export

/**
 * The choices for one export: conversation id, which message blocks are checked, and content options.
 * When [allSelected] is true, [blocks] is not consulted (everything is selected by default); an empty set with [allSelected] false means nothing at all.
 */
data class ExportSelection(
    val conversationId: String,
    val blocks: Set<ExportBlockId> = emptySet(),
    val allSelected: Boolean = true,
)

@JvmInline
value class ExportBlockId(val value: String)

/** Stable ids of message blocks; the conversation is append-only, so turn numbers and steer positions never change after being stored. */
object ExportIds {
    fun user(turn: Int) = ExportBlockId("u$turn")
    fun steer(turn: Int, index: Int) = ExportBlockId("m$turn-$index")
    fun answer(turn: Int) = ExportBlockId("r$turn")
    fun files(turn: Int) = ExportBlockId("f$turn")
}

enum class ExportKind { SingleFile, ZipBundle }

enum class ExportCapability {
    ToolSteps, Attachments, Screens, Timestamps, ModelName,
    Preview, Share, Paging, Streaming, Background, RequiresActivity,
}

/** A format visible to the user; multiple backend engines may be registered under the same [id]. */
data class ExportFormat(
    val id: String,
    val nameKey: String,
    val mime: String,
    val extension: String,
    val defaultKind: ExportKind = ExportKind.SingleFile,
)

data class ExportDescriptor(
    val id: String,
    val formatId: String,
    val extension: String,
    val mime: String,
    val kind: ExportKind,
    val capabilities: Set<ExportCapability> = emptySet(),
    val presentationKeys: Set<String> = emptySet(),
    val maxBlocksPerVolume: Int? = null,
)

data class ExportContent(
    val includeToolSteps: Boolean = false,
    val includeAttachments: Boolean = true,
    val includeScreens: Boolean = true,
    val includeTimestamps: Boolean = false,
    val includeModel: Boolean = false,
    val includeSystemPrompt: Boolean = false,
    val redaction: RedactionLevel = RedactionLevel.Secrets,
)

data class ExportPresentation(
    val theme: ExportTheme = ExportTheme.Auto,
    val pageSize: PageSize = PageSize.A4,
    val imageQuality: Int = 85,
)

enum class ExportTheme { Auto, Light, Dark }
enum class PageSize { A4, Letter }
enum class RedactionLevel { Secrets, SecretsAndPii }

data class ExportWarning(val code: String, val detail: String)

enum class AssetKind { Image, File, UserScreen }

/** Platform-independent asset source; opened by [ExportAssetReader] on the app side. */
sealed interface AssetRequest {
    data class Workspace(val path: String) : AssetRequest
    data class UserScreen(val screenId: String) : AssetRequest
    data class ContentUri(val uri: String) : AssetRequest
}

/** Metadata only; bytes are not put into the AST. */
data class ExportAsset(
    val id: String,
    val kind: AssetKind,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val request: AssetRequest,
)

data class QuotedText(val from: QuoteSource, val text: String)

enum class QuoteSource { User, Mika }

data class PageRef(val url: String, val title: String, val selection: String?)

data class ExportFileRef(
    val name: String,
    val sizeBytes: Long,
    val request: AssetRequest,
)

sealed interface AnswerOutcome {
    data object Succeeded : AnswerOutcome
    data class Unverified(val note: String?, val unconfirmed: List<String>) : AnswerOutcome
    data class Failed(val reason: String) : AnswerOutcome
    data class Cancelled(val summary: String?) : AnswerOutcome
}

data class ToolStep(
    val step: Int,
    val note: String?,
    val tool: String?,
    val argsJson: String?,
    val result: String?,
    val isError: Boolean,
    val durationMs: Long,
    val model: String?,
)

sealed interface ExportBlock {
    val id: ExportBlockId

    data class UserMessage(
        override val id: ExportBlockId,
        val text: String,
        val quote: QuotedText?,
        val page: PageRef?,
        val attachments: List<ExportAsset>,
        val screens: List<ExportAsset>,
        val atMs: Long?,
    ) : ExportBlock

    data class SteerMessage(
        override val id: ExportBlockId,
        val text: String,
        val interruptedText: String?,
        val finishedText: String?,
        val assets: List<ExportAsset> = emptyList(),
    ) : ExportBlock

    data class Answer(
        override val id: ExportBlockId,
        val markdown: String,
        val outcome: AnswerOutcome,
        val atMs: Long?,
        val model: String?,
        val toolSteps: List<ToolStep>,
        val files: List<ExportAsset>,
    ) : ExportBlock
}

data class ExportMeta(
    val conversationId: String,
    val title: String,
    val exportedAtMs: Long,
    val appVersion: String,
    val language: String,
)

data class ExportDocument(
    val meta: ExportMeta,
    val blocks: List<ExportBlock>,
    val assets: List<ExportAsset>,
    val warnings: List<ExportWarning> = emptyList(),
)
