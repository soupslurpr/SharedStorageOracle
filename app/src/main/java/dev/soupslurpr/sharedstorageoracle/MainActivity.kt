package dev.soupslurpr.sharedstorageoracle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.math.BigInteger
import kotlin.math.floor
import kotlin.math.log10
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BROWSER_TAB_INDEX = 0
private const val NAMESPACE_TAB_INDEX = 1
private const val EXACT_PATH_TAB_INDEX = 2

/** Hosts the storage-permissionless metadata browser and exact-path probe. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OracleTheme {
                ProbeScreen()
            }
        }
    }
}

/** Applies system dynamic colors that follow the system night mode. */
@Composable
private fun OracleTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        },
        content = content,
    )
}

/** Owns browser, generated-namespace, and exact-path probe state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProbeScreen() {
    var selectedTab by rememberSaveable { mutableIntStateOf(BROWSER_TAB_INDEX) }
    var exactPath by rememberSaveable { mutableStateOf(DEFAULT_TEST_PATH) }
    var probeReport by remember { mutableStateOf<ProbeReport?>(null) }
    var isProbing by remember { mutableStateOf(false) }
    var probeJob by remember { mutableStateOf<Job?>(null) }
    var probeRequestId by remember { mutableIntStateOf(0) }

    var directoryInput by rememberSaveable { mutableStateOf(DEFAULT_BROWSER_PATH) }
    var directoryListing by remember { mutableStateOf<BrowserListing?>(null) }
    var isLoadingDirectory by remember { mutableStateOf(false) }
    var browserJob by remember { mutableStateOf<Job?>(null) }
    var browserRequestId by remember { mutableIntStateOf(0) }

    var namespaceRoot by rememberSaveable { mutableStateOf(DEFAULT_NAMESPACE_ROOT) }
    var namespaceMaxBytes by rememberSaveable {
        mutableStateOf(DEFAULT_NAMESPACE_MAX_BYTES.toString())
    }
    var namespaceCandidateLimit by rememberSaveable {
        mutableStateOf(DEFAULT_NAMESPACE_CANDIDATE_LIMIT.toString())
    }
    var namespaceReport by remember { mutableStateOf<NamespaceScanReport?>(null) }
    var namespaceProgress by remember { mutableStateOf<NamespaceScanProgress?>(null) }
    var namespaceMessage by remember { mutableStateOf<String?>(null) }
    var isScanningNamespace by remember { mutableStateOf(false) }
    var namespaceJob by remember { mutableStateOf<Job?>(null) }
    var namespaceRequestId by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()

    val inspectPath: (String, Boolean) -> Unit = { rawPath, switchToProbe ->
        val candidatePath = rawPath.trim()
        if (candidatePath.isNotEmpty()) {
            probeJob?.cancel()
            probeRequestId += 1
            val requestId = probeRequestId
            exactPath = candidatePath
            if (switchToProbe) {
                selectedTab = EXACT_PATH_TAB_INDEX
            }
            probeJob = scope.launch {
                isProbing = true
                try {
                    val nextReport = withContext(Dispatchers.IO) {
                        PathProbe.inspect(candidatePath)
                    }
                    if (requestId == probeRequestId) {
                        probeReport = nextReport
                    }
                } finally {
                    if (requestId == probeRequestId) {
                        isProbing = false
                    }
                }
            }
        }
    }

    val openDirectory: (String) -> Unit = { rawPath ->
        val candidatePath = rawPath.trim()
        if (candidatePath.isNotEmpty()) {
            browserJob?.cancel()
            browserRequestId += 1
            val requestId = browserRequestId
            directoryInput = candidatePath
            browserJob = scope.launch {
                isLoadingDirectory = true
                try {
                    val nextListing = withContext(Dispatchers.IO) {
                        DirectoryBrowser.list(candidatePath)
                    }
                    if (requestId == browserRequestId) {
                        directoryListing = nextListing
                        directoryInput = nextListing.path
                    }
                } finally {
                    if (requestId == browserRequestId) {
                        isLoadingDirectory = false
                    }
                }
            }
        }
    }

    val scanNamespace: () -> Unit = scan@{
        val maxUtf8Bytes = namespaceMaxBytes.toIntOrNull()
        val candidateLimit = namespaceCandidateLimit.toLongOrNull()
        if (maxUtf8Bytes == null || candidateLimit == null) {
            namespaceMessage = "Enter whole numbers for the byte bound and candidate cap."
            return@scan
        }
        namespaceJob?.cancel()
        namespaceRequestId += 1
        val requestId = namespaceRequestId
        namespaceReport = null
        namespaceProgress = null
        namespaceMessage = null
        namespaceJob = scope.launch {
            isScanningNamespace = true
            try {
                val nextReport = withContext(Dispatchers.IO) {
                    NamespaceScanner.scan(
                        rawRoot = namespaceRoot,
                        maxUtf8Bytes = maxUtf8Bytes,
                        candidateLimit = candidateLimit,
                    ) { progress ->
                        withContext(Dispatchers.Main.immediate) {
                            if (requestId == namespaceRequestId) {
                                namespaceProgress = progress
                            }
                        }
                    }
                }
                if (requestId == namespaceRequestId) {
                    namespaceReport = nextReport
                }
            } catch (_: CancellationException) {
                if (requestId == namespaceRequestId) {
                    val attempted = namespaceProgress?.attemptedCandidates ?: 0
                    namespaceMessage = "Scan cancelled after $attempted generated names."
                }
            } finally {
                if (requestId == namespaceRequestId) {
                    isScanningNamespace = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        openDirectory(directoryInput)
        inspectPath(exactPath, false)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Storage existence oracle") })
        },
        modifier = Modifier.fillMaxSize(),
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == BROWSER_TAB_INDEX,
                    onClick = { selectedTab = BROWSER_TAB_INDEX },
                    text = { Text("Browse") },
                )
                Tab(
                    selected = selectedTab == NAMESPACE_TAB_INDEX,
                    onClick = { selectedTab = NAMESPACE_TAB_INDEX },
                    text = { Text("Namespace") },
                )
                Tab(
                    selected = selectedTab == EXACT_PATH_TAB_INDEX,
                    onClick = { selectedTab = EXACT_PATH_TAB_INDEX },
                    text = { Text("Exact path") },
                )
            }

            when (selectedTab) {
                BROWSER_TAB_INDEX -> BrowserList(
                    directoryInput = directoryInput,
                    onDirectoryInputChange = { directoryInput = it },
                    onOpenDirectory = openDirectory,
                    onInspectPath = { inspectPath(it, true) },
                    isLoading = isLoadingDirectory,
                    listing = directoryListing,
                    modifier = Modifier.weight(1f),
                )
                NAMESPACE_TAB_INDEX -> NamespaceScanList(
                    root = namespaceRoot,
                    onRootChange = { namespaceRoot = it },
                    maxUtf8Bytes = namespaceMaxBytes,
                    onMaxUtf8BytesChange = { namespaceMaxBytes = it },
                    candidateLimit = namespaceCandidateLimit,
                    onCandidateLimitChange = { namespaceCandidateLimit = it },
                    onUseCompleteDemo = {
                        namespaceMaxBytes = DEFAULT_NAMESPACE_MAX_BYTES.toString()
                        namespaceCandidateLimit = DEFAULT_NAMESPACE_CANDIDATE_LIMIT.toString()
                    },
                    onUseFullNamespace = {
                        namespaceMaxBytes = FULL_NAMESPACE_MAX_BYTES.toString()
                        namespaceCandidateLimit = DEFAULT_NAMESPACE_CANDIDATE_LIMIT.toString()
                    },
                    onRunScan = scanNamespace,
                    onCancelScan = { namespaceJob?.cancel() },
                    onInspectPath = { inspectPath(it, true) },
                    isRunning = isScanningNamespace,
                    progress = namespaceProgress,
                    report = namespaceReport,
                    message = namespaceMessage,
                    modifier = Modifier.weight(1f),
                )
                else -> ExactPathProbeList(
                    path = exactPath,
                    onPathChange = { exactPath = it },
                    onRunProbe = { inspectPath(exactPath, false) },
                    onUseKnownPath = { exactPath = DEFAULT_TEST_PATH },
                    onUseMissingPath = { exactPath = MISSING_TEST_PATH },
                    isRunning = isProbing,
                    report = probeReport,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Renders a file-manager-style view of names returned for one directory. */
@Composable
private fun BrowserList(
    directoryInput: String,
    onDirectoryInputChange: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onInspectPath: (String) -> Unit,
    isLoading: Boolean,
    listing: BrowserListing?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            BrowserExplanationCard()
        }
        item {
            OutlinedTextField(
                value = directoryInput,
                onValueChange = onDirectoryInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Directory path") },
                supportingText = { Text("Shows immediate child names Android returns to this app.") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onOpenDirectory(directoryInput) }),
                minLines = 2,
                maxLines = 4,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { listing?.parentPath?.let(onOpenDirectory) },
                    enabled = listing?.parentPath != null && !isLoading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Up")
                }
                OutlinedButton(
                    onClick = { onOpenDirectory(listing?.path ?: directoryInput) },
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Refresh")
                }
                Button(
                    onClick = { onOpenDirectory(directoryInput) },
                    enabled = directoryInput.isNotBlank() && !isLoading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Open")
                }
            }
        }
        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        listing?.let { currentListing ->
            item {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val entryCount = currentListing.entries.size
                        Text(
                            text = if (entryCount == 1) {
                                "1 entry Android returned"
                            } else {
                                "$entryCount entries Android returned"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = currentListing.path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            currentListing.error?.let { error ->
                item {
                    BrowserMessageCard(
                        title = "Directory could not be listed",
                        message = error,
                        isError = true,
                    )
                }
            } ?: if (currentListing.entries.isEmpty() && !isLoading) {
                item {
                    BrowserMessageCard(
                        title = "No names returned",
                        message = "The directory may be empty, or Android may have filtered its entries. " +
                            "A known child can still be tested in Exact path.",
                        isError = false,
                    )
                }
            } else {
                items(currentListing.entries, key = { it.path }) { entry ->
                    BrowserEntryCard(
                        entry = entry,
                        onClick = {
                            if (entry.kind == BrowserEntryKind.DIRECTORY) {
                                onOpenDirectory(entry.path)
                            } else {
                                onInspectPath(entry.path)
                            }
                        },
                    )
                }
            }
        }
        item {
            PermissionFooter()
        }
    }
}

/** Renders controls and file-manager rows for generated-name probing. */
@Composable
private fun NamespaceScanList(
    root: String,
    onRootChange: (String) -> Unit,
    maxUtf8Bytes: String,
    onMaxUtf8BytesChange: (String) -> Unit,
    candidateLimit: String,
    onCandidateLimitChange: (String) -> Unit,
    onUseCompleteDemo: () -> Unit,
    onUseFullNamespace: () -> Unit,
    onRunScan: () -> Unit,
    onCancelScan: () -> Unit,
    onInspectPath: (String) -> Unit,
    isRunning: Boolean,
    progress: NamespaceScanProgress?,
    report: NamespaceScanReport?,
    message: String?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            NamespaceExplanationCard()
        }
        item {
            OutlinedTextField(
                value = root,
                onValueChange = onRootChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Directory path") },
                supportingText = { Text("Generated strings are resolved directly below this path.") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                minLines = 2,
                maxLines = 4,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onUseCompleteDemo,
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Complete ≤2 bytes")
                }
                OutlinedButton(
                    onClick = onUseFullNamespace,
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Full 255 bytes")
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = maxUtf8Bytes,
                    onValueChange = onMaxUtf8BytesChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Max UTF-8 bytes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = candidateLimit,
                    onValueChange = onCandidateLimitChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Candidate cap") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        }
        item {
            Button(
                onClick = if (isRunning) onCancelScan else onRunScan,
                enabled = root.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel direct scan")
                } else {
                    Text("Scan generated path strings")
                }
            }
        }
        if (isRunning && progress != null) {
            item {
                NamespaceProgressCard(progress)
            }
        }
        message?.let { currentMessage ->
            item {
                BrowserMessageCard(
                    title = "Namespace scan stopped",
                    message = currentMessage,
                    isError = false,
                )
            }
        }
        report?.let { currentReport ->
            item {
                NamespaceSummaryCard(currentReport)
            }
            if (currentReport.error == null && currentReport.hits.isEmpty()) {
                item {
                    BrowserMessageCard(
                        title = "No generated names matched",
                        message = "This only rules out the candidates actually tested.",
                        isError = false,
                    )
                }
            }
            items(currentReport.hits, key = { "namespace:${it.path}" }) { hit ->
                NamespaceHitCard(hit = hit, onClick = { onInspectPath(hit.path) })
            }
        }
        item {
            PermissionFooter()
        }
    }
}

/** Explains the direct string-generation experiment. */
@Composable
private fun NamespaceExplanationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Complete string namespace",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Generates every valid Unicode-scalar filename in increasing UTF-8 byte length " +
                    "and calls Files.exists on each exact path. It does not list, walk, or create anything.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Displays live direct-scan throughput. */
@Composable
private fun NamespaceProgressCard(progress: NamespaceScanProgress) {
    Card(modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Direct scan in progress",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${progress.attemptedCandidates} exact paths tested",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "current encoded length = ${progress.currentUtf8Bytes} bytes",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "hits = ${progress.hitCount}; elapsed = ${progress.elapsedMilliseconds} ms",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** Summarizes completeness, throughput, and namespace size. */
@Composable
private fun NamespaceSummaryCard(report: NamespaceScanReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (report.error == null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = "Generated-string result",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                report.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } ?: run {
                    val candidatesPerSecond = candidatesPerSecond(report)
                    Text(
                        text = "${report.attemptedCandidates} of " +
                            "${formatCandidateCount(report.totalCandidates)} candidates tested",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "completed namespace = ${report.completedNamespace}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "${report.elapsedMilliseconds} ms; $candidatesPerSecond candidates/s",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "hits = ${report.hitCount}; recorded = ${report.hits.size}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (!report.completedNamespace) {
                        Text(
                            text = projectedDuration(report.totalCandidates, candidatesPerSecond),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = "No directory enumeration, Files.walk, or file creation was used.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Displays one exact generated-name hit as a file-manager row. */
@Composable
private fun NamespaceHitCard(hit: NamespaceHit, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NamespaceHitBadge(hit.kind)
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = escapeFilename(hit.name),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = namespaceHitKindDescription(hit.kind),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    hit.sizeBytes?.let { sizeBytes ->
                        Text(
                            text = "$sizeBytes bytes",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    hit.metadataError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/** Renders a compact type marker for a generated-name hit. */
@Composable
private fun NamespaceHitBadge(kind: NamespaceHitKind) {
    val label = when (kind) {
        NamespaceHitKind.DIRECTORY -> "DIR"
        NamespaceHitKind.REGULAR_FILE -> "FILE"
        NamespaceHitKind.SYMBOLIC_LINK -> "LINK"
        NamespaceHitKind.OTHER -> "OTHER"
        NamespaceHitKind.UNKNOWN -> "?"
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Returns a user-facing type label for a generated-name hit. */
private fun namespaceHitKindDescription(kind: NamespaceHitKind): String = when (kind) {
    NamespaceHitKind.DIRECTORY -> "Directory—tap for exact probe"
    NamespaceHitKind.REGULAR_FILE -> "Regular file—tap for exact probe"
    NamespaceHitKind.SYMBOLIC_LINK -> "Symbolic link—tap for exact probe"
    NamespaceHitKind.OTHER -> "Other filesystem object—tap for exact probe"
    NamespaceHitKind.UNKNOWN -> "Exists; metadata unavailable"
}

/** Returns measured candidate throughput rounded down to a whole number. */
private fun candidatesPerSecond(report: NamespaceScanReport): Long {
    val elapsedMilliseconds = maxOf(1L, report.elapsedMilliseconds)
    return report.attemptedCandidates * 1_000L / elapsedMilliseconds
}

/** Formats a very large candidate count without rendering hundreds of digits. */
private fun formatCandidateCount(value: BigInteger): String {
    val decimal = value.toString()
    if (decimal.length <= 18) {
        return decimal
    }
    return "${decimal.first()}.${decimal.substring(1, 6)} × 10^${decimal.length - 1} " +
        "(${decimal.length} digits)"
}

/** Projects completion time from the measured direct-probe throughput. */
private fun projectedDuration(totalCandidates: BigInteger, candidatesPerSecond: Long): String {
    if (candidatesPerSecond <= 0) {
        return "Completion time cannot be projected from this run."
    }
    val log10Years = log10(totalCandidates) -
        log10(candidatesPerSecond.toDouble()) -
        log10(365.25 * 24.0 * 60.0 * 60.0)
    return if (log10Years < 0) {
        "At this rate, the selected namespace would take less than one year."
    } else {
        "At this rate, the selected namespace would take about 10^" +
            "${floor(log10Years).toLong()} years."
    }
}

/** Approximates the base-10 logarithm of a positive arbitrary-size integer. */
private fun log10(value: BigInteger): Double {
    require(value.signum() > 0)
    val decimal = value.toString()
    val prefixText = decimal.take(16)
    val prefix = prefixText.toDouble()
    return decimal.length - prefixText.length + log10(prefix)
}

/** Escapes control and non-ASCII characters for an unambiguous filename label. */
private fun escapeFilename(name: String): String = buildString {
    var offset = 0
    while (offset < name.length) {
        val codePoint = name.codePointAt(offset)
        if (codePoint in 0x20..0x7e) {
            appendCodePoint(codePoint)
        } else {
            append("U+")
            append(codePoint.toString(16).uppercase().padStart(4, '0'))
        }
        offset += Character.charCount(codePoint)
    }
}

/** Explains why a browser listing can differ from an exact-path probe. */
@Composable
private fun BrowserExplanationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Directory browser",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Tap returned folders to navigate. This shows every name Android returns, " +
                    "which may be less than everything physically present in the folder.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Shows a directory-listing error or empty/filtering explanation. */
@Composable
private fun BrowserMessageCard(title: String, message: String, isError: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Displays one returned directory entry as a tappable file-manager row. */
@Composable
private fun BrowserEntryCard(entry: BrowserEntry, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrowserEntryBadge(entry.kind)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = entryKindDescription(entry.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.kind != BrowserEntryKind.DIRECTORY && entry.sizeBytes != null) {
                    Text(
                        text = "${entry.sizeBytes} bytes",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                entry.lastModified?.let { lastModified ->
                    Text(
                        text = "Modified $lastModified",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entry.metadataError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** Renders a compact kind marker for a browser entry. */
@Composable
private fun BrowserEntryBadge(kind: BrowserEntryKind) {
    val label = when (kind) {
        BrowserEntryKind.DIRECTORY -> "DIR"
        BrowserEntryKind.REGULAR_FILE -> "FILE"
        BrowserEntryKind.SYMBOLIC_LINK -> "LINK"
        BrowserEntryKind.OTHER -> "OTHER"
        BrowserEntryKind.UNKNOWN -> "?"
    }
    val containerColor = if (kind == BrowserEntryKind.DIRECTORY) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (kind == BrowserEntryKind.DIRECTORY) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Returns a user-facing description for a browser entry kind. */
private fun entryKindDescription(kind: BrowserEntryKind): String = when (kind) {
    BrowserEntryKind.DIRECTORY -> "Directory—tap to open"
    BrowserEntryKind.REGULAR_FILE -> "Regular file—tap for exact probe"
    BrowserEntryKind.SYMBOLIC_LINK -> "Symbolic link—tap for exact probe"
    BrowserEntryKind.OTHER -> "Other filesystem object—tap for exact probe"
    BrowserEntryKind.UNKNOWN -> "Name returned; metadata unavailable"
}

/** Renders the scrollable exact-path controls and report. */
@Composable
private fun ExactPathProbeList(
    path: String,
    onPathChange: (String) -> Unit,
    onRunProbe: () -> Unit,
    onUseKnownPath: () -> Unit,
    onUseMissingPath: () -> Unit,
    isRunning: Boolean,
    report: ProbeReport?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ExactProbeExplanationCard()
        }
        item {
            OutlinedTextField(
                value = path,
                onValueChange = onPathChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Exact absolute path") },
                supportingText = { Text("The app only reads metadata and attempts a one-byte read.") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onRunProbe() }),
                minLines = 2,
                maxLines = 4,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onUseKnownPath,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Known test file", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onUseMissingPath,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Missing twin", maxLines = 1)
                }
            }
        }
        item {
            Button(
                onClick = onRunProbe,
                enabled = path.isNotBlank() && !isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Probing…")
                } else {
                    Text("Run read-only probe")
                }
            }
        }
        report?.let { currentReport ->
            item {
                SelectionContainer {
                    Column {
                        Text(
                            text = "Result for",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = currentReport.path,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            items(currentReport.checks, key = { it.title }) { check ->
                CheckCard(check)
            }
        }
        item {
            PermissionFooter()
        }
    }
}

/** Explains the exact-path existence oracle. */
@Composable
private fun ExactProbeExplanationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Exact-path probe",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "An app without storage permissions can ask about a guessed shared-storage path. " +
                    "Metadata success is shown separately from directory enumeration and content access.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Shows one filesystem operation, its disposition, and returned values. */
@Composable
private fun CheckCard(check: ProbeCheck) {
    Card(modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = check.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    DispositionBadge(check.disposition)
                }
                Text(
                    text = check.outcome,
                    style = MaterialTheme.typography.bodyLarge,
                )
                check.details.forEach { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** Maps a probe disposition to a compact Material status badge. */
@Composable
private fun DispositionBadge(disposition: ProbeDisposition) {
    val (label, containerColor, contentColor) = dispositionStyle(disposition)
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Returns the label and colors for a probe disposition. */
@Composable
private fun dispositionStyle(disposition: ProbeDisposition): DispositionStyle = when (disposition) {
    ProbeDisposition.METADATA_RETURNED -> DispositionStyle(
        "METADATA",
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
    ProbeDisposition.CONTENT_READABLE -> DispositionStyle(
        "READABLE",
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
    ProbeDisposition.ACCESS_BLOCKED -> DispositionStyle(
        "BLOCKED",
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
    )
    ProbeDisposition.NOT_FOUND -> DispositionStyle(
        "NOT FOUND",
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ProbeDisposition.NO_ENTRIES -> DispositionStyle(
        "NO NAMES",
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
    )
    ProbeDisposition.NOT_APPLICABLE -> DispositionStyle(
        "N/A",
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ProbeDisposition.INDETERMINATE -> DispositionStyle(
        "UNKNOWN",
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
    )
    ProbeDisposition.ERROR -> DispositionStyle(
        "ERROR",
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
}

/** Shows the app's relevant permission boundary. */
@Composable
private fun PermissionFooter() {
    Column {
        Text(
            text = "No storage, media, or all-files permissions are declared in the manifest.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** Holds the presentation values for a disposition badge. */
private data class DispositionStyle(
    val label: String,
    val containerColor: Color,
    val contentColor: Color,
)

/** Previews the browser-first probe screen. */
@Preview(showBackground = true)
@Composable
private fun ProbeScreenPreview() {
    OracleTheme {
        ProbeScreen()
    }
}
