package dev.soupslurpr.sharedstorageoracle

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

internal const val DEFAULT_BROWSER_PATH = "/storage/emulated/0/Download"

/** Classifies one name returned by directory enumeration. */
internal enum class BrowserEntryKind(val sortOrder: Int) {
    DIRECTORY(0),
    REGULAR_FILE(1),
    SYMBOLIC_LINK(2),
    OTHER(3),
    UNKNOWN(4),
}

/** Describes one child name and the metadata Android returned for it. */
internal data class BrowserEntry(
    val name: String,
    val path: String,
    val kind: BrowserEntryKind,
    val sizeBytes: Long?,
    val lastModified: String?,
    val metadataError: String?,
)

/** Contains one directory listing exactly as exposed to this app. */
internal data class BrowserListing(
    val path: String,
    val parentPath: String?,
    val entries: List<BrowserEntry>,
    val error: String?,
)

/** Enumerates a directory without requesting permissions or changing storage. */
internal object DirectoryBrowser {
    private val entryComparator = Comparator<BrowserEntry> { left, right ->
        val kindComparison = left.kind.sortOrder.compareTo(right.kind.sortOrder)
        if (kindComparison != 0) {
            kindComparison
        } else {
            val caseInsensitiveComparison = left.name.compareTo(right.name, ignoreCase = true)
            if (caseInsensitiveComparison != 0) {
                caseInsensitiveComparison
            } else {
                left.name.compareTo(right.name)
            }
        }
    }

    /** Returns every immediate child path surfaced by `Files.newDirectoryStream`. */
    fun list(rawPath: String): BrowserListing {
        val candidate = rawPath.trim()
        if (candidate.isEmpty()) {
            return errorListing(rawPath, "enter an absolute directory path")
        }

        val path = try {
            Paths.get(candidate).normalize()
        } catch (exception: InvalidPathException) {
            return errorListing(rawPath, exception.message ?: "invalid path")
        }
        if (!path.isAbsolute) {
            return errorListing(rawPath, "enter an absolute path beginning with /")
        }

        return try {
            val entries = Files.newDirectoryStream(path).use { directory ->
                directory.mapTo(mutableListOf()) { child -> inspect(child) }
            }
            entries.sortWith(entryComparator)
            BrowserListing(
                path = path.toString(),
                parentPath = path.parent?.toString(),
                entries = entries,
                error = null,
            )
        } catch (exception: Exception) {
            BrowserListing(
                path = path.toString(),
                parentPath = path.parent?.toString(),
                entries = emptyList(),
                error = exception.rootCause().describe(),
            )
        }
    }

    private fun inspect(path: Path): BrowserEntry {
        val name = path.fileName?.toString() ?: path.toString()
        return try {
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            BrowserEntry(
                name = name,
                path = path.toString(),
                kind = attributes.kind(),
                sizeBytes = attributes.size(),
                lastModified = attributes.lastModifiedTime().toString(),
                metadataError = null,
            )
        } catch (exception: Exception) {
            BrowserEntry(
                name = name,
                path = path.toString(),
                kind = BrowserEntryKind.UNKNOWN,
                sizeBytes = null,
                lastModified = null,
                metadataError = exception.rootCause().describe(),
            )
        }
    }

    private fun BasicFileAttributes.kind(): BrowserEntryKind = when {
        isDirectory -> BrowserEntryKind.DIRECTORY
        isRegularFile -> BrowserEntryKind.REGULAR_FILE
        isSymbolicLink -> BrowserEntryKind.SYMBOLIC_LINK
        else -> BrowserEntryKind.OTHER
    }

    private fun errorListing(path: String, error: String) = BrowserListing(
        path = path,
        parentPath = null,
        entries = emptyList(),
        error = error,
    )

    private fun Throwable.rootCause(): Throwable {
        var root = this
        while (root.cause != null && root.cause !== root) {
            root = root.cause!!
        }
        return root
    }

    private fun Throwable.describe(): String {
        val type = javaClass.simpleName.ifEmpty { javaClass.name }
        return "$type: ${message ?: "no additional detail"}"
    }
}
