package com.aliedu.kern.core.workspace

import java.io.File

/**
 * Where Kern keeps the projects it creates itself, and how their directories are named.
 *
 * These directories form the **projects root**. Inside the Linux guest the whole root is bound at
 * `/workspace`, so a project named `first_app` is `/workspace/first_app` there and `ls /workspace`
 * lists every project the user created — which is exactly what the terminal and the agent see.
 *
 * One directory per project, named after the project rather than after a UUID: the name is
 * something the user can type at a shell prompt, and something they can recognise in a listing.
 */
object AppWorkspacePaths {

    /** Directory under `filesDir` that holds every app-created project. */
    const val DIR_NAME = "workspaces"

    fun root(filesDir: File): File = File(filesDir, DIR_NAME).apply { mkdirs() }

    /**
     * A filesystem-safe directory name for [name]. Spaces become underscores, anything that is not
     * a letter, digit, dot, underscore or dash is dropped, and an empty result becomes `project`.
     */
    fun slug(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("[\\s]+"), "_")
            .replace(Regex("[^A-Za-z0-9._-]"), "")
            .trim('.', '_', '-')
        return cleaned.ifBlank { "project" }
    }

    /**
     * A free directory under [root] for a project called [name]. A name that is already taken gets
     * a numeric suffix (`notes`, `notes_2`) instead of reusing or clobbering the existing project.
     */
    fun directoryFor(root: File, name: String): File {
        val base = slug(name)
        var candidate = File(root, base)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(root, base + "_" + suffix)
            suffix++
        }
        return candidate
    }
}
