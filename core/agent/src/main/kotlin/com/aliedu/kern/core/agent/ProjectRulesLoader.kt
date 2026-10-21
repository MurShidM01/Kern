package com.aliedu.kern.core.agent

import com.aliedu.kern.core.common.redaction.Redactor
import com.aliedu.kern.core.workspace.WorkspaceAccessException
import com.aliedu.kern.core.workspace.WorkspaceAdapter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads an optional `KERN.md` from the workspace root and hands it to the model as *project rules*
 * (PRD §4.3 bullet 6). Two hard limits keep it honest:
 *
 *  - It is only read when the workspace access is currently valid, so a revoked or external
 *    workspace contributes nothing.
 *  - It is injected as delimited **data**, never as instructions to execute. The system prompt
 *    already tells the model that file content is untrusted; this keeps the same contract for a
 *    file whose whole purpose is to look like instructions.
 */
@Singleton
class ProjectRulesLoader @Inject constructor() {

    /** Returns the rules text, or null when there is nothing safe to load. */
    suspend fun load(adapter: WorkspaceAdapter): String? {
        if (!adapter.isAccessValid()) return null
        val text = try {
            if (!adapter.exists(FILE_NAME)) return null
            adapter.readText(FILE_NAME, MAX_BYTES).text
        } catch (e: WorkspaceAccessException) {
            return null
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return Redactor.redactText(trimmed.take(MAX_CHARS), emptyList())
    }

    companion object {
        const val FILE_NAME = "KERN.md"
        const val MAX_CHARS = 8_000
        private const val MAX_BYTES = 16_000L
    }
}
