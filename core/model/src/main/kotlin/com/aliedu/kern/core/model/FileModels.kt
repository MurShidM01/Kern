package com.aliedu.kern.core.model

/** A single entry in a workspace tree (PRD 7.3, 9.2 list_files). */
data class FileNode(
    val name: String,
    val path: String,
    val kind: FileKind,
    val sizeBytes: Long?,
    val lastModified: Long?
)

/** Result of reading a text file, with truncation/encoding metadata (PRD 9.2 read_file). */
data class FileContent(
    val path: String,
    val text: String,
    val encoding: String,
    val sizeBytes: Long,
    val truncated: Boolean
)

enum class SearchMatchKind { FILENAME, CONTENT }

/** A single search hit (PRD 9.2 search_files). */
data class SearchMatch(
    val path: String,
    val kind: SearchMatchKind,
    val lineNumber: Int?,
    val lineText: String?
)

/** Outcome of a workspace write (PRD 7.5 save status). */
data class SaveResult(
    val path: String,
    val bytesWritten: Long,
    val savedAt: Long
)

/** True when a filename/extension is treated as a text file for read/edit. */
object FileClassifier {
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "kt", "kts", "java", "xml", "json", "yaml", "yml",
        "toml", "properties", "gradle", "pro", "kt.tmp", "cfg", "ini", "csv", "tsv",
        "js", "ts", "jsx", "tsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp",
        "cs", "swift", "sh", "bash", "zsh", "fish", "sql", "html", "htm", "css",
        "scss", "less", "vue", "svelte", "dart", "lua", "pl", "r", "php", "env",
        "gitignore", "editorconfig", "log", "diff", "patch"
    )

    fun extensionOf(name: String): String {
        val idx = name.lastIndexOf('.')
        if (idx <= 0 || idx == name.length - 1) return ""
        return name.substring(idx + 1).lowercase()
    }

    fun isLikelyText(name: String): Boolean {
        val ext = extensionOf(name)
        if (ext.isEmpty()) {
            // Common extension-less text files.
            return name in setOf(
                "README", "LICENSE", "NOTICE", "Makefile", "Dockerfile", "Procfile",
                ".gitignore", ".editorconfig", ".env", "gradlew", "settings.gradle"
            )
        }
        return ext in TEXT_EXTENSIONS
    }
}
