package dev.soupslurpr.sharedstorageoracle

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val DEFAULT_NAMESPACE_ROOT =
    "/storage/emulated/0/Download/ExistenceOracleProbe"
internal const val DEFAULT_NAMESPACE_MAX_BYTES = 2
internal const val DEFAULT_NAMESPACE_CANDIDATE_LIMIT = 100_000L
internal const val FULL_NAMESPACE_MAX_BYTES = 255

private const val MAX_CANDIDATE_LIMIT = 10_000_000L
private const val PROGRESS_INTERVAL = 2_048L
private const val MAX_RECORDED_HITS = 500
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L

private const val ONE_BYTE_CODE_POINT_COUNT = 126L
private const val TWO_BYTE_CODE_POINT_COUNT = 1_920L
private const val THREE_BYTE_CODE_POINT_COUNT = 61_440L
private const val FOUR_BYTE_CODE_POINT_COUNT = 1_048_576L

/** Classifies one exact child path discovered by generated-name probing. */
internal enum class NamespaceHitKind {
    DIRECTORY,
    REGULAR_FILE,
    SYMBOLIC_LINK,
    OTHER,
    UNKNOWN,
}

/** Describes metadata returned for one generated filename that exists. */
internal data class NamespaceHit(
    val name: String,
    val path: String,
    val kind: NamespaceHitKind,
    val sizeBytes: Long?,
    val metadataError: String?,
)

/** Describes live progress from a direct filename-namespace scan. */
internal data class NamespaceScanProgress(
    val attemptedCandidates: Long,
    val currentUtf8Bytes: Int,
    val hitCount: Long,
    val elapsedMilliseconds: Long,
)

/** Contains the result of probing generated child names under one directory. */
internal data class NamespaceScanReport(
    val root: String,
    val maxUtf8Bytes: Int,
    val totalCandidates: BigInteger,
    val candidateLimit: Long,
    val attemptedCandidates: Long,
    val hitCount: Long,
    val hits: List<NamespaceHit>,
    val elapsedMilliseconds: Long,
    val completedNamespace: Boolean,
    val error: String?,
)

/** Exhaustively generates UTF-8 child-name strings and probes exact paths. */
internal object NamespaceScanner {
    /** Probes names in increasing UTF-8 length without listing the directory. */
    suspend fun scan(
        rawRoot: String,
        maxUtf8Bytes: Int,
        candidateLimit: Long,
        onProgress: suspend (NamespaceScanProgress) -> Unit,
    ): NamespaceScanReport {
        val startedAt = System.nanoTime()
        val root = parseRoot(rawRoot) ?: return errorReport(
            rawRoot = rawRoot,
            maxUtf8Bytes = maxUtf8Bytes,
            candidateLimit = candidateLimit,
            startedAt = startedAt,
            error = "enter an absolute directory path",
        )
        if (maxUtf8Bytes !in 1..FULL_NAMESPACE_MAX_BYTES) {
            return errorReport(
                rawRoot = root.toString(),
                maxUtf8Bytes = maxUtf8Bytes,
                candidateLimit = candidateLimit,
                startedAt = startedAt,
                error = "maximum UTF-8 bytes must be between 1 and $FULL_NAMESPACE_MAX_BYTES",
            )
        }
        if (candidateLimit !in 1..MAX_CANDIDATE_LIMIT) {
            return errorReport(
                rawRoot = root.toString(),
                maxUtf8Bytes = maxUtf8Bytes,
                candidateLimit = candidateLimit,
                startedAt = startedAt,
                error = "candidate cap must be between 1 and $MAX_CANDIDATE_LIMIT",
            )
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return errorReport(
                rawRoot = root.toString(),
                maxUtf8Bytes = maxUtf8Bytes,
                candidateLimit = candidateLimit,
                startedAt = startedAt,
                error = "root is not a visible directory",
            )
        }

        val totalCandidates = countValidNames(maxUtf8Bytes)
        val enumerator = Utf8NameEnumerator(maxUtf8Bytes)
        val coroutineContext = currentCoroutineContext()
        val hits = mutableListOf<NamespaceHit>()
        var attemptedCandidates = 0L
        var hitCount = 0L
        var currentUtf8Bytes = 1

        while (attemptedCandidates < candidateLimit) {
            coroutineContext.ensureActive()
            val candidate = enumerator.next() ?: break
            currentUtf8Bytes = candidate.utf8Bytes
            val path = root.resolve(candidate.name)
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                hitCount += 1
                if (hits.size < MAX_RECORDED_HITS) {
                    hits += inspect(candidate.name, path)
                }
            }
            attemptedCandidates += 1

            if (attemptedCandidates % PROGRESS_INTERVAL == 0L) {
                onProgress(
                    NamespaceScanProgress(
                        attemptedCandidates = attemptedCandidates,
                        currentUtf8Bytes = currentUtf8Bytes,
                        hitCount = hitCount,
                        elapsedMilliseconds = elapsedSince(startedAt),
                    ),
                )
            }
        }

        val completedNamespace = BigInteger.valueOf(attemptedCandidates) == totalCandidates
        onProgress(
            NamespaceScanProgress(
                attemptedCandidates = attemptedCandidates,
                currentUtf8Bytes = currentUtf8Bytes,
                hitCount = hitCount,
                elapsedMilliseconds = elapsedSince(startedAt),
            ),
        )
        return NamespaceScanReport(
            root = root.toString(),
            maxUtf8Bytes = maxUtf8Bytes,
            totalCandidates = totalCandidates,
            candidateLimit = candidateLimit,
            attemptedCandidates = attemptedCandidates,
            hitCount = hitCount,
            hits = hits,
            elapsedMilliseconds = elapsedSince(startedAt),
            completedNamespace = completedNamespace,
            error = null,
        )
    }

    private fun parseRoot(rawRoot: String): Path? {
        val candidate = rawRoot.trim()
        if (candidate.isEmpty()) {
            return null
        }
        return try {
            Paths.get(candidate).normalize().takeIf(Path::isAbsolute)
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun inspect(name: String, path: Path): NamespaceHit = try {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        NamespaceHit(
            name = name,
            path = path.toString(),
            kind = attributes.kind(),
            sizeBytes = attributes.size(),
            metadataError = null,
        )
    } catch (exception: Exception) {
        NamespaceHit(
            name = name,
            path = path.toString(),
            kind = NamespaceHitKind.UNKNOWN,
            sizeBytes = null,
            metadataError = exception.rootCause().describe(),
        )
    }

    private fun BasicFileAttributes.kind(): NamespaceHitKind = when {
        isDirectory -> NamespaceHitKind.DIRECTORY
        isRegularFile -> NamespaceHitKind.REGULAR_FILE
        isSymbolicLink -> NamespaceHitKind.SYMBOLIC_LINK
        else -> NamespaceHitKind.OTHER
    }

    private fun errorReport(
        rawRoot: String,
        maxUtf8Bytes: Int,
        candidateLimit: Long,
        startedAt: Long,
        error: String,
    ) = NamespaceScanReport(
        root = rawRoot,
        maxUtf8Bytes = maxUtf8Bytes,
        totalCandidates = BigInteger.ZERO,
        candidateLimit = candidateLimit,
        attemptedCandidates = 0,
        hitCount = 0,
        hits = emptyList(),
        elapsedMilliseconds = elapsedSince(startedAt),
        completedNamespace = false,
        error = error,
    )

    private fun elapsedSince(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / NANOSECONDS_PER_MILLISECOND

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

/** Counts valid Unicode-scalar filenames through a maximum UTF-8 byte length. */
internal fun countValidNames(maxUtf8Bytes: Int): BigInteger {
    require(maxUtf8Bytes in 1..FULL_NAMESPACE_MAX_BYTES)
    val countsByBytes = Array(maxUtf8Bytes + 1) { BigInteger.ZERO }
    countsByBytes[0] = BigInteger.ONE
    var total = BigInteger.ZERO
    for (byteLength in 1..maxUtf8Bytes) {
        var count = countsByBytes[byteLength - 1]
            .multiply(BigInteger.valueOf(ONE_BYTE_CODE_POINT_COUNT))
        if (byteLength >= 2) {
            count = count.add(
                countsByBytes[byteLength - 2]
                    .multiply(BigInteger.valueOf(TWO_BYTE_CODE_POINT_COUNT)),
            )
        }
        if (byteLength >= 3) {
            count = count.add(
                countsByBytes[byteLength - 3]
                    .multiply(BigInteger.valueOf(THREE_BYTE_CODE_POINT_COUNT)),
            )
        }
        if (byteLength >= 4) {
            count = count.add(
                countsByBytes[byteLength - 4]
                    .multiply(BigInteger.valueOf(FOUR_BYTE_CODE_POINT_COUNT)),
            )
        }
        countsByBytes[byteLength] = count
        total = total.add(count)
    }

    // A lone dot and two dots are path syntax rather than child filenames.
    return total.subtract(BigInteger.valueOf(if (maxUtf8Bytes == 1) 1 else 2))
}

private data class Utf8NameCandidate(
    val name: String,
    val utf8Bytes: Int,
)

/** Enumerates Unicode-scalar strings by their exact UTF-8 byte length. */
private class Utf8NameEnumerator(private val maxUtf8Bytes: Int) {
    private val codePoints = IntArray(maxUtf8Bytes)
    private val nextCodePoints = IntArray(maxUtf8Bytes)
    private var targetBytes = 1
    private var usedBytes = 0
    private var depth = 0
    private var initialized = false

    /** Returns the next valid child filename or null after exhausting the bound. */
    fun next(): Utf8NameCandidate? {
        while (targetBytes <= maxUtf8Bytes) {
            if (!initialized) {
                depth = 0
                usedBytes = 0
                nextCodePoints[0] = Character.MIN_CODE_POINT + 1
                initialized = true
            }

            val remainingBytes = targetBytes - usedBytes
            val codePoint = findNextCodePoint(nextCodePoints[depth], remainingBytes)
            if (codePoint == null) {
                if (depth == 0) {
                    targetBytes += 1
                    initialized = false
                } else {
                    depth -= 1
                    usedBytes -= utf8Width(codePoints[depth])
                }
                continue
            }

            nextCodePoints[depth] = codePoint + 1
            codePoints[depth] = codePoint
            val width = utf8Width(codePoint)
            if (width < remainingBytes) {
                usedBytes += width
                depth += 1
                nextCodePoints[depth] = Character.MIN_CODE_POINT + 1
                continue
            }

            val name = String(codePoints, 0, depth + 1)
            if (name == "." || name == "..") {
                continue
            }
            return Utf8NameCandidate(name = name, utf8Bytes = targetBytes)
        }
        return null
    }

    private fun findNextCodePoint(start: Int, remainingBytes: Int): Int? {
        val maxCodePoint = when (remainingBytes) {
            1 -> 0x7f
            2 -> 0x7ff
            3 -> 0xffff
            else -> Character.MAX_CODE_POINT
        }
        var codePoint = start
        while (codePoint <= maxCodePoint) {
            if (codePoint != '/'.code &&
                codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
            ) {
                return codePoint
            }
            codePoint += 1
        }
        return null
    }

    private fun utf8Width(codePoint: Int): Int = when {
        codePoint <= 0x7f -> 1
        codePoint <= 0x7ff -> 2
        codePoint <= 0xffff -> 3
        else -> 4
    }
}
