package dev.soupslurpr.sharedstorageoracle

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimespec
import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val DEFAULT_TEST_PATH =
    "/storage/emulated/0/Download/ExistenceOracleProbe/known-37-bytes.bin"
internal const val MISSING_TEST_PATH =
    "/storage/emulated/0/Download/ExistenceOracleProbe/missing-twin.bin"

private const val MAX_LISTED_NAMES = 100
private const val MAX_COMMAND_OUTPUT_CHARACTERS = 4_096

/** Classifies the observable result of one read-only filesystem operation. */
internal enum class ProbeDisposition {
    METADATA_RETURNED,
    CONTENT_READABLE,
    ACCESS_BLOCKED,
    NOT_FOUND,
    NO_ENTRIES,
    NOT_APPLICABLE,
    INDETERMINATE,
    ERROR,
}

/** Describes one operation and everything it revealed to the app. */
internal data class ProbeCheck(
    val title: String,
    val outcome: String,
    val details: List<String>,
    val disposition: ProbeDisposition,
)

/** Contains the normalized path and ordered read-only checks shown by the UI. */
internal data class ProbeReport(
    val path: String,
    val checks: List<ProbeCheck>,
)

/** Runs non-mutating Java NIO and Android stat probes for one exact path. */
internal object PathProbe {
    private val timestampFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

    /** Inspects an absolute path without creating, changing, or deleting it. */
    fun inspect(rawPath: String): ProbeReport {
        val path = try {
            Paths.get(rawPath).normalize()
        } catch (exception: InvalidPathException) {
            return invalidPathReport(rawPath, exception.message ?: "invalid path")
        }

        if (!path.isAbsolute) {
            return invalidPathReport(rawPath, "enter an absolute path beginning with /")
        }

        val existence = probeExistence(path)
        return ProbeReport(
            path = path.toString(),
            checks = listOf(
                existence.check,
                probeBasicAttributes(path),
                probeStat(path),
                probeLs(path),
                probeDirectoryListing(path, existence),
                probeContent(path, existence),
            ),
        )
    }

    private fun invalidPathReport(rawPath: String, detail: String) = ProbeReport(
        path = rawPath,
        checks = listOf(
            ProbeCheck(
                title = "Path validation",
                outcome = "Invalid path",
                details = listOf(detail),
                disposition = ProbeDisposition.ERROR,
            ),
        ),
    )

    private fun probeExistence(path: Path): ExistenceProbe {
        val exists = Files.exists(path)
        val notExists = Files.notExists(path)
        val isDirectory = Files.isDirectory(path)
        val isRegularFile = Files.isRegularFile(path)
        val isSymbolicLink = Files.isSymbolicLink(path)
        val outcome: String
        val disposition: ProbeDisposition
        when {
            exists -> {
                outcome = "Exact path exists"
                disposition = ProbeDisposition.METADATA_RETURNED
            }
            notExists -> {
                outcome = "Exact path is absent"
                disposition = ProbeDisposition.NOT_FOUND
            }
            else -> {
                outcome = "Existence is hidden or indeterminate"
                disposition = ProbeDisposition.INDETERMINATE
            }
        }

        return ExistenceProbe(
            check = ProbeCheck(
                title = "Exact-path oracle (java.nio.file.Files)",
                outcome = outcome,
                details = listOf(
                    "Files.exists = $exists",
                    "Files.notExists = $notExists",
                    "Files.isRegularFile = $isRegularFile",
                    "Files.isDirectory = $isDirectory",
                    "Files.isSymbolicLink = $isSymbolicLink",
                ),
                disposition = disposition,
            ),
            notExists = notExists,
            isDirectory = isDirectory,
        )
    }

    private fun probeBasicAttributes(path: Path): ProbeCheck = try {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        ProbeCheck(
            title = "Basic attributes (Files.readAttributes)",
            outcome = "Metadata returned",
            details = listOf(
                "regular file = ${attributes.isRegularFile}",
                "directory = ${attributes.isDirectory}",
                "symbolic link = ${attributes.isSymbolicLink}",
                "other type = ${attributes.isOther}",
                "size = ${attributes.size()} bytes",
                "last modified = ${formatTime(attributes.lastModifiedTime())}",
                "last accessed = ${formatTime(attributes.lastAccessTime())}",
                "creation time = ${formatTime(attributes.creationTime())}",
                "file key = ${attributes.fileKey() ?: "not supplied"}",
            ),
            disposition = ProbeDisposition.METADATA_RETURNED,
        )
    } catch (exception: Exception) {
        failureCheck("Basic attributes (Files.readAttributes)", exception)
    }

    private fun probeStat(path: Path): ProbeCheck = try {
        val stat = Os.lstat(path.toString())
        ProbeCheck(
            title = "Low-level metadata (Os.lstat)",
            outcome = "Full stat structure returned",
            details = listOf(
                "device = ${stat.st_dev}",
                "inode = ${stat.st_ino}",
                "mode = 0${Integer.toOctalString(stat.st_mode)}",
                "hard links = ${stat.st_nlink}",
                "uid = ${stat.st_uid}",
                "gid = ${stat.st_gid}",
                "special device = ${stat.st_rdev}",
                "size = ${stat.st_size} bytes",
                "block size = ${stat.st_blksize} bytes",
                "allocated blocks = ${stat.st_blocks}",
                "access time = ${formatTime(stat.st_atim)}",
                "modified time = ${formatTime(stat.st_mtim)}",
                "status-change time (not creation) = ${formatTime(stat.st_ctim)}",
            ),
            disposition = ProbeDisposition.METADATA_RETURNED,
        )
    } catch (exception: Exception) {
        failureCheck("Low-level metadata (Os.lstat)", exception)
    }

    private fun probeLs(path: Path): ProbeCheck = try {
        val process = ProcessBuilder("/system/bin/ls", "-ld", path.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { reader ->
            reader.readText().take(MAX_COMMAND_OUTPUT_CHARACTERS).trimEnd()
        }
        val exitCode = process.waitFor()
        val disposition = when {
            exitCode == 0 -> ProbeDisposition.METADATA_RETURNED
            output.isAccessDeniedMessage() -> ProbeDisposition.ACCESS_BLOCKED
            output.isNotFoundMessage() -> ProbeDisposition.NOT_FOUND
            else -> ProbeDisposition.ERROR
        }
        val outcome = when (disposition) {
            ProbeDisposition.METADATA_RETURNED -> "Metadata printed for exact path"
            ProbeDisposition.ACCESS_BLOCKED -> "Access blocked"
            ProbeDisposition.NOT_FOUND -> "Path not found"
            else -> "Command failed"
        }
        ProbeCheck(
            title = "System command (/system/bin/ls -ld)",
            outcome = outcome,
            details = buildList {
                add("exit code = $exitCode")
                if (output.isNotEmpty()) {
                    addAll(output.lineSequence().map { "output = $it" })
                }
            },
            disposition = disposition,
        )
    } catch (exception: Exception) {
        failureCheck("System command (/system/bin/ls -ld)", exception)
    }

    private fun probeDirectoryListing(path: Path, existence: ExistenceProbe): ProbeCheck {
        if (!existence.isDirectory) {
            return ProbeCheck(
                title = "Directory enumeration (Files.newDirectoryStream)",
                outcome = if (existence.notExists) "Path not found" else "Not a directory",
                details = emptyList(),
                disposition = if (existence.notExists) {
                    ProbeDisposition.NOT_FOUND
                } else {
                    ProbeDisposition.NOT_APPLICABLE
                },
            )
        }

        return try {
            val names = mutableListOf<String>()
            val truncated = Files.newDirectoryStream(path).use { entries ->
                val iterator = entries.iterator()
                while (iterator.hasNext() && names.size < MAX_LISTED_NAMES) {
                    names += iterator.next().fileName.toString()
                }
                iterator.hasNext()
            }
            names.sort()
            val details = buildList {
                addAll(names.map { "name = $it" })
                if (truncated) {
                    add("more names were omitted after $MAX_LISTED_NAMES entries")
                }
            }
            ProbeCheck(
                title = "Directory enumeration (Files.newDirectoryStream)",
                outcome = if (names.isEmpty()) {
                    "No names returned (empty or filtered)"
                } else {
                    "${names.size}${if (truncated) "+" else ""} name(s) returned"
                },
                details = details,
                disposition = if (names.isEmpty()) {
                    ProbeDisposition.NO_ENTRIES
                } else {
                    ProbeDisposition.METADATA_RETURNED
                },
            )
        } catch (exception: Exception) {
            failureCheck("Directory enumeration (Files.newDirectoryStream)", exception)
        }
    }

    private fun probeContent(path: Path, existence: ExistenceProbe): ProbeCheck {
        if (existence.isDirectory) {
            return ProbeCheck(
                title = "Content access (Files.newInputStream)",
                outcome = "Not attempted for a directory",
                details = emptyList(),
                disposition = ProbeDisposition.NOT_APPLICABLE,
            )
        }

        return try {
            val bytesRead = Files.newInputStream(path).use { input ->
                if (input.read() == -1) 0 else 1
            }
            ProbeCheck(
                title = "Content access (Files.newInputStream)",
                outcome = if (bytesRead == 0) "File opened; it is empty" else "File opened; one byte read",
                details = listOf("The byte value is intentionally not displayed."),
                disposition = ProbeDisposition.CONTENT_READABLE,
            )
        } catch (exception: Exception) {
            failureCheck("Content access (Files.newInputStream)", exception)
        }
    }

    private fun failureCheck(title: String, exception: Exception): ProbeCheck {
        val root = exception.rootCause()
        val disposition = when {
            root.isAccessDenied() -> ProbeDisposition.ACCESS_BLOCKED
            root.isNotFound() -> ProbeDisposition.NOT_FOUND
            else -> ProbeDisposition.ERROR
        }
        val outcome = when (disposition) {
            ProbeDisposition.ACCESS_BLOCKED -> "Access blocked"
            ProbeDisposition.NOT_FOUND -> "Path not found"
            else -> "Operation failed"
        }
        return ProbeCheck(
            title = title,
            outcome = outcome,
            details = listOf(root.describe()),
            disposition = disposition,
        )
    }

    private fun Throwable.rootCause(): Throwable {
        var root = this
        while (root.cause != null && root.cause !== root) {
            root = root.cause!!
        }
        return root
    }

    private fun Throwable.isAccessDenied(): Boolean {
        if (this is AccessDeniedException || this is SecurityException) {
            return true
        }
        if (this is ErrnoException && (errno == OsConstants.EACCES || errno == OsConstants.EPERM)) {
            return true
        }
        val detail = message.orEmpty()
        return detail.contains("EACCES", ignoreCase = true) ||
            detail.contains("permission denied", ignoreCase = true) ||
            detail.contains("operation not permitted", ignoreCase = true)
    }

    private fun Throwable.isNotFound(): Boolean {
        if (this is NoSuchFileException) {
            return true
        }
        if (this is ErrnoException && errno == OsConstants.ENOENT) {
            return true
        }
        if (this is FileNotFoundException && message.orEmpty().contains("ENOENT", ignoreCase = true)) {
            return true
        }
        return message.orEmpty().contains("no such file", ignoreCase = true)
    }

    private fun String.isAccessDeniedMessage(): Boolean =
        contains("permission denied", ignoreCase = true) ||
            contains("operation not permitted", ignoreCase = true)

    private fun String.isNotFoundMessage(): Boolean =
        contains("no such file", ignoreCase = true) ||
            contains("not found", ignoreCase = true)

    private fun Throwable.describe(): String {
        val type = javaClass.simpleName.ifEmpty { javaClass.name }
        return "$type: ${message ?: "no additional detail"}"
    }

    private fun formatTime(time: FileTime): String = formatTime(time.toInstant())

    private fun formatTime(time: StructTimespec): String {
        val instant = Instant.ofEpochSecond(time.tv_sec, time.tv_nsec)
        return "${formatTime(instant)} (${time.tv_sec}s + ${time.tv_nsec}ns)"
    }

    private fun formatTime(instant: Instant): String = timestampFormatter.format(instant)

    private data class ExistenceProbe(
        val check: ProbeCheck,
        val notExists: Boolean,
        val isDirectory: Boolean,
    )
}
