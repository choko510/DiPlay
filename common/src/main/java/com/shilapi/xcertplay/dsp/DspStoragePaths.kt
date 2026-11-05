package com.shilapi.xcertplay.dsp

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal object DspStoragePaths {
    fun directoryUnder(filesDirectory: File, vararg segments: String): File? = runCatching {
        val root = filesDirectory.canonicalFile
        var parent = root
        for (segment in segments) {
            if (!isSafeName(segment)) return null
            val child = File(parent, segment)
            if (Files.isSymbolicLink(child.toPath())) return null
            val canonicalChild = child.canonicalFile
            if (canonicalChild.parentFile != parent || (child.exists() && !child.isDirectory)) return null
            parent = canonicalChild
        }
        parent
    }.getOrNull()

    fun fileIn(directory: File, name: String): File? = runCatching {
        if (!isSafeName(name) || Files.isSymbolicLink(File(directory, name).toPath())) return null
        val canonicalDirectory = directory.canonicalFile
        val canonicalFile = File(canonicalDirectory, name).canonicalFile
        canonicalFile.takeIf { it.parentFile == canonicalDirectory }
    }.getOrNull()

    fun entryExists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun isSafeName(value: String): Boolean = value.isNotBlank() && value != "." && value != ".." &&
        '/' !in value && '\\' !in value && '\u0000' !in value
}
