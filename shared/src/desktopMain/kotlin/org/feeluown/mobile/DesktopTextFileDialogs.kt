package org.feeluown.mobile

/** File payload returned by the desktop-native file dialog boundary. */
data class DesktopTextFile(
    val fileName: String,
    val content: String,
)

/**
 * Host-provided desktop file dialogs. Implementations are intentionally outside `shared` so the
 * Tao/GraalVM host never needs to initialize Swing/AWT just to import or export a local playlist.
 */
interface DesktopTextFileDialogProvider {
    fun openTextFile(
        dialogTitle: String,
        filterDescription: String,
        extensions: List<String>,
    ): DesktopTextFile?

    fun saveTextFile(
        dialogTitle: String,
        suggestedFileName: String,
        filterDescription: String,
        extensions: List<String>,
        content: String,
    ): Boolean
}

@Volatile
private var desktopTextFileDialogProviderFactory: (() -> DesktopTextFileDialogProvider)? = null

fun installDesktopTextFileDialogProviderFactory(factory: () -> DesktopTextFileDialogProvider) {
    desktopTextFileDialogProviderFactory = factory
}

private fun createDesktopTextFileDialogProvider(): DesktopTextFileDialogProvider? =
    desktopTextFileDialogProviderFactory?.invoke()

internal fun openDesktopTextFile(
    dialogTitle: String,
    filterDescription: String,
    extensions: List<String>,
    onFeedback: (String) -> Unit,
): DesktopTextFile? = runCatching {
    val provider = createDesktopTextFileDialogProvider()
        ?: error("桌面文件选择器未初始化")
    provider.openTextFile(dialogTitle, filterDescription, extensions)
}.onFailure { throwable ->
    onFeedback(throwable.message ?: "无法读取文件")
}.getOrNull()

internal fun saveDesktopTextFile(
    dialogTitle: String,
    suggestedFileName: String,
    filterDescription: String,
    extensions: List<String>,
    content: String,
    onFeedback: (String) -> Unit,
): Boolean = runCatching {
    val provider = createDesktopTextFileDialogProvider()
        ?: error("桌面文件选择器未初始化")
    provider.saveTextFile(
        dialogTitle = dialogTitle,
        suggestedFileName = suggestedFileName,
        filterDescription = filterDescription,
        extensions = extensions,
        content = content,
    )
}.onFailure { throwable ->
    onFeedback(throwable.message ?: "写入文件失败")
}.getOrDefault(false)
