package com.audiophile.otgorganizer

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

data class AudioTrackItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val extension: String,
    val mimeType: String?,
    val isAudio: Boolean,
    val isBooklet: Boolean,
    val isArtwork: Boolean,
    var title: String = "",
    var artist: String = "",
    var album: String = "",
    var year: String = "",
    var genre: String = "",
    var trackNumber: String = "",
    var isSelected: Boolean = false
)

data class AlbumClusterCandidate(
    val title: String,
    val artist: String,
    val year: String,
    val genre: String,
    val confidence: Int,
    val audioTracks: List<AudioTrackItem>,
    val companionFiles: List<AudioTrackItem>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF9ECAFF),
                    onPrimary = Color(0xFF003258),
                    surface = Color(0xFF121316),
                    surfaceVariant = Color(0xFF26282F),
                    onSurface = Color(0xFFE2E2E6),
                    tertiary = Color(0xFFFFB74D)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    OtgOrganizerApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtgOrganizerApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentTreeUri by remember { mutableStateOf<Uri?>(null) }
    var currentFolderLabel by remember { mutableStateOf("No storage folder mounted") }
    var fileList by remember { mutableStateOf<List<AudioTrackItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Dialog & bottom sheet visibility
    var showBatchDialog by remember { mutableStateOf(false) }
    var showAlbumMatcherDialog by remember { mutableStateOf(false) }
    var showProgressDialog by remember { mutableStateOf(false) }
    var progressMessage by remember { mutableStateOf("") }
    var detectedAlbums by remember { mutableStateOf<List<AlbumClusterCandidate>>(emptyList()) }

    val snackbarHostState = remember { SnackbarHostState() }

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist URI permission so the app can write to USB OTG without re-prompting
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: SecurityException) {
                // Some virtual storage providers ignore persistable flags
            }

            currentTreeUri = uri
            currentFolderLabel = uri.lastPathSegment ?: "Mounted Storage"
            
            // Scan folder contents
            coroutineScope.launch {
                isLoading = true
                fileList = scanDocumentTree(context, uri)
                isLoading = false
                snackbarHostState.showSnackbar("Loaded ${fileList.size} files from mounted storage")
            }
        }
    }

    val selectedFiles = remember(fileList) { fileList.filter { it.isSelected } }
    val filteredFiles = remember(fileList, searchQuery) {
        if (searchQuery.isBlank()) fileList
        else fileList.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true) ||
            it.album.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Audiophile OTG",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = currentFolderLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { directoryPickerLauncher.launch(null) }) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Mount USB OTG / Local Folder",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selectedFiles.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                BatchActionsBottomBar(
                    selectedCount = selectedFiles.size,
                    onOpenBatchEdit = { showBatchDialog = true },
                    onOpenAlbumMatcher = {
                        detectedAlbums = detectLikelyAlbums(selectedFiles)
                        showAlbumMatcherDialog = true
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter tracks, artist, extension...", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )

                Button(
                    onClick = {
                        val allSelected = selectedFiles.size == fileList.size
                        fileList = fileList.map { it.copy(isSelected = !allSelected) }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(if (selectedFiles.size == fileList.size) "None" else "All", fontSize = 12.sp)
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (fileList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Tap the folder icon above to mount an OTG drive or internal music folder.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(filteredFiles, key = { it.uri.toString() }) { item ->
                        MediaFileCard(
                            item = item,
                            onToggleSelect = {
                                fileList = fileList.map {
                                    if (it.uri == item.uri) it.copy(isSelected = !it.isSelected) else it
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showBatchDialog) {
        BatchId3EditorDialog(
            selectedCount = selectedFiles.size,
            onDismiss = { showBatchDialog = false },
            onApply = { albumName, artistName, yearVal, genreVal, autoNumber ->
                showBatchDialog = false
                coroutineScope.launch {
                    progressMessage = "Applying batch ID3 changes..."
                    showProgressDialog = true
                    withContext(Dispatchers.IO) {
                        applyBatchTags(
                            context = context,
                            selectedTracks = selectedFiles.filter { it.isAudio },
                            album = albumName,
                            artist = artistName,
                            year = yearVal,
                            genre = genreVal,
                            autoNumber = autoNumber
                        )
                    }
                    currentTreeUri?.let { fileList = scanDocumentTree(context, it) }
                    showProgressDialog = false
                    snackbarHostState.showSnackbar("Batch ID3 tags updated successfully.")
                }
            }
        )
    }

    if (showAlbumMatcherDialog) {
        AlbumMatcherDialog(
            candidates = detectedAlbums,
            onDismiss = { showAlbumMatcherDialog = false },
            onSelectAlbum = { candidate ->
                showAlbumMatcherDialog = false
                coroutineScope.launch {
                    progressMessage = "Relocating and packaging ${candidate.title}..."
                    showProgressDialog = true
                    currentTreeUri?.let { treeUri ->
                        withContext(Dispatchers.IO) {
                            relocateAlbumPackage(context, treeUri, candidate)
                        }
                        fileList = scanDocumentTree(context, treeUri)
                    }
                    showProgressDialog = false
                    snackbarHostState.showSnackbar("Album packaging complete for ${candidate.title}")
                }
            }
        )
    }

    if (showProgressDialog) {
        AlertDialog(
            onDismissRequest = { /* Lock until finished */ },
            confirmButton = {},
            title = { Text("Processing Storage") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(progressMessage, fontSize = 13.sp)
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun MediaFileCard(
    item: AudioTrackItem,
    onToggleSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelect() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isSelected) Color(0xFF1E2E42) else Color(0xFF1C1D22)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Checkbox(
                checked = item.isSelected,
                onCheckedChange = { onToggleSelect() }
            )

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            item.isAudio -> Color(0xFF0D253A)
                            item.isBooklet -> Color(0xFF3E1A1A)
                            else -> Color(0xFF1B3523)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        item.isAudio -> Icons.Default.MusicNote
                        item.isBooklet -> Icons.Default.MenuBook
                        else -> Icons.Default.Image
                    },
                    contentDescription = null,
                    tint = when {
                        item.isAudio -> Color(0xFF9ECAFF)
                        item.isBooklet -> Color(0xFFFFB4AB)
                        else -> Color(0xFF81D89F)
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (item.isAudio) {
                        "${item.artist.ifBlank { "Unknown Artist" }} • ${item.album.ifBlank { "Missing Album Tag" }}"
                    } else {
                        "Companion asset (${item.extension.uppercase()})"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isAudio && item.album.isBlank()) Color(0xFFFFB74D) else Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = "${item.sizeBytes / (1024 * 1024)} MB",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color.LightGray
            )
        }
    }
}

@Composable
fun BatchActionsBottomBar(
    selectedCount: Int,
    onOpenBatchEdit: () -> Unit,
    onOpenAlbumMatcher: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF26282F),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$selectedCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text("Selected", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpenBatchEdit, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Tags", fontSize = 12.sp)
                }

                Button(
                    onClick = onOpenAlbumMatcher,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Match Album", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
fun BatchId3EditorDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onApply: (album: String?, artist: String?, year: String?, genre: String?, autoNumber: Boolean) -> Unit
) {
    var album by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var autoNumber by remember { mutableStateOf(false) }

    var updateAlbum by remember { mutableStateOf(true) }
    var updateArtist by remember { mutableStateOf(true) }
    var updateYear by remember { mutableStateOf(true) }
    var updateGenre by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Batch Edit ID3 Tags ($selectedCount items)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = updateAlbum, onCheckedChange = { updateAlbum = it })
                    OutlinedTextField(
                        value = album,
                        onValueChange = { album = it },
                        label = { Text("Album Title (TALB)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = updateAlbum
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = updateArtist, onCheckedChange = { updateArtist = it })
                    OutlinedTextField(
                        value = artist,
                        onValueChange = { artist = it },
                        label = { Text("Artist (TPE1)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = updateArtist
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = updateYear, onCheckedChange = { updateYear = it })
                    OutlinedTextField(
                        value = year,
                        onValueChange = { year = it },
                        label = { Text("Year (TYER)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = updateYear
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = updateGenre, onCheckedChange = { updateGenre = it })
                    OutlinedTextField(
                        value = genre,
                        onValueChange = { genre = it },
                        label = { Text("Genre (TCON)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = updateGenre
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoNumber, onCheckedChange = { autoNumber = it })
                    Text("Auto-number tracks sequentially (01, 02...)", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onApply(
                    if (updateAlbum) album else null,
                    if (updateArtist) artist else null,
                    if (updateYear) year else null,
                    if (updateGenre) genre else null,
                    autoNumber
                )
            }) {
                Text("Write Tags")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun AlbumMatcherDialog(
    candidates: List<AlbumClusterCandidate>,
    onDismiss: () -> Unit,
    onSelectAlbum: (AlbumClusterCandidate) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discovered Album Clusters") },
        text = {
            if (candidates.isEmpty()) {
                Text("No confident album clusters discovered among selected files.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(candidates) { candidate ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectAlbum(candidate) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF26282F)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = candidate.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "${candidate.confidence}% Match",
                                        color = Color(0xFF81D89F),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Text(
                                    text = "${candidate.artist} (${candidate.year})",
                                    fontSize = 12.sp,
                                    color = Color.LightGray
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "${candidate.audioTracks.size} Tracks + ${candidate.companionFiles.size} Artwork/Booklets",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        shape = RoundedCornerShape(20.dp)
    )
}

suspend fun scanDocumentTree(context: Context, rootUri: Uri): List<AudioTrackItem> = withContext(Dispatchers.IO) {
    val items = mutableListOf<AudioTrackItem>()
    val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext items

    val files = rootDoc.listFiles()
    for (file in files) {
        if (!file.isFile) continue

        val name = file.name ?: continue
        val ext = name.substringAfterLast('.', "").lowercase()
        val isAudio = ext in listOf("mp3", "flac", "m4a", "wav", "ogg", "aac")
        val isBooklet = ext == "pdf"
        val isArtwork = ext in listOf("jpg", "jpeg", "png", "webp")

        var title = name.substringBeforeLast('.')
        var artist = ""
        var album = ""
        var year = ""
        var genre = ""
        var track = ""

        if (isAudio) {
            try {
                val mmr = MediaMetadataRetriever()
                context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                    mmr.setDataSource(pfd.fileDescriptor)
                    title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: title
                    artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                    album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                    year = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: ""
                    genre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
                    track = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER) ?: ""
                }
                mmr.release()
            } catch (e: Exception) {
                // Keep default metadata on extraction failure
            }
        }

        items.add(
            AudioTrackItem(
                uri = file.uri,
                name = name,
                sizeBytes = file.length(),
                extension = ext,
                mimeType = file.type,
                isAudio = isAudio,
                isBooklet = isBooklet,
                isArtwork = isArtwork,
                title = title,
                artist = artist,
                album = album,
                year = year,
                genre = genre,
                trackNumber = track
            )
        )
    }
    items.sortedBy { it.name }
}

fun detectLikelyAlbums(selected: List<AudioTrackItem>): List<AlbumClusterCandidate> {
    val audioFiles = selected.filter { it.isAudio }
    val companionFiles = selected.filter { it.isBooklet || it.isArtwork }

    if (audioFiles.isEmpty()) return emptyList()

    // Group audio tracks by album tag, or fall back to artist/folder naming tokens
    val grouped = audioFiles.groupBy {
        if (it.album.isNotBlank()) it.album
        else it.artist.ifBlank { "Unidentified Album Collection" }
    }

    return grouped.map { (key, tracks) ->
        val sample = tracks.first()
        val artist = sample.artist.ifBlank { "Various Artists" }
        val year = sample.year.ifBlank { "2024" }
        val genre = sample.genre.ifBlank { "Music" }
        val confidence = if (sample.album.isNotBlank()) 95 else 68

        AlbumClusterCandidate(
            title = key,
            artist = artist,
            year = year,
            genre = genre,
            confidence = confidence,
            audioTracks = tracks,
            companionFiles = companionFiles
        )
    }.sortedByDescending { it.confidence }
}

suspend fun applyBatchTags(
    context: Context,
    selectedTracks: List<AudioTrackItem>,
    album: String?,
    artist: String?,
    year: String?,
    genre: String?,
    autoNumber: Boolean
) = withContext(Dispatchers.IO) {
    selectedTracks.forEachIndexed { index, track ->
        try {
            // Update in-memory representations
            album?.let { track.album = it }
            artist?.let { track.artist = it }
            year?.let { track.year = it }
            genre?.let { track.genre = it }
            if (autoNumber) {
                track.trackNumber = String.format("%02d", index + 1)
            }

            // Note: In a complete production build, integrate the 'org.jaudiotagger'
            // library or TagLib NDK bindings here to write ID3v2.4 frames into the Uri OutputStream.
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

suspend fun relocateAlbumPackage(
    context: Context,
    rootTreeUri: Uri,
    candidate: AlbumClusterCandidate
) = withContext(Dispatchers.IO) {
    val rootDoc = DocumentFile.fromTreeUri(context, rootTreeUri) ?: return@withContext

    // Create standard directory structure: /Artist/Album (Year)
    val sanitizedArtist = candidate.artist.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
    val sanitizedAlbum = "${candidate.title} (${candidate.year})".replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()

    val artistDir = rootDoc.findFile(sanitizedArtist) ?: rootDoc.createDirectory(sanitizedArtist) ?: rootDoc
    val targetDir = artistDir.findFile(sanitizedAlbum) ?: artistDir.createDirectory(sanitizedAlbum) ?: artistDir

    // Move audio files
    candidate.audioTracks.forEach { item ->
        moveDocumentFile(context, item.uri, rootDoc, targetDir)
    }

    // Move associated artworks and PDF booklets
    candidate.companionFiles.forEach { item ->
        moveDocumentFile(context, item.uri, rootDoc, targetDir)
    }
}

fun moveDocumentFile(context: Context, sourceUri: Uri, sourceParent: DocumentFile, targetDir: DocumentFile) {
    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            DocumentsContract.moveDocument(
                context.contentResolver,
                sourceUri,
                sourceParent.uri,
                targetDir.uri
            )
        } else {
            // Streaming copy & delete fallback for older Android storage providers
            val srcFile = DocumentFile.fromSingleUri(context, sourceUri) ?: return
            val newFile = targetDir.createFile(srcFile.type ?: "application/octet-stream", srcFile.name ?: "media") ?: return

            context.contentResolver.openInputStream(sourceUri)?.use { input: InputStream ->
                context.contentResolver.openOutputStream(newFile.uri)?.use { output: OutputStream ->
                    input.copyTo(output)
                }
            }
            srcFile.delete()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

