@file:OptIn(ExperimentalMaterial3Api::class)

package org.sunsetware.phocid.ui.views

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.io.FilenameUtils
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.sunsetware.phocid.MainViewModel
import org.sunsetware.phocid.R
import org.sunsetware.phocid.TopLevelScreen
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.globals.Strings
import org.sunsetware.phocid.utils.icuFormat

/** Supported audio formats for tag editing */
private val SUPPORTED_TAG_FORMATS = setOf(
    "mp3", "flac", "ogg", "m4a", "mp4", "wma", "wav", "aif", "aiff", "dsf"
)

/** Result class for tag saving operation */
private sealed class SaveTagsResult {
    data object Success : SaveTagsResult()
    data class Error(val message: String) : SaveTagsResult()
}

@Stable
class EditTagsScreen(private val track: Track) : TopLevelScreen() {
    @Composable
    override fun Compose(viewModel: MainViewModel) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val scrollState = rememberScrollState()
        val uiManager = viewModel.uiManager

        // Editable fields
        var title by rememberSaveable { mutableStateOf(track.title ?: "") }
        var artist by rememberSaveable { mutableStateOf(track.artists.joinToString(", ")) }
        var album by rememberSaveable { mutableStateOf(track.album ?: "") }
        var albumArtist by rememberSaveable { mutableStateOf(track.albumArtists.joinToString(", ")) }
        var genre by rememberSaveable { mutableStateOf(track.genres.joinToString(", ")) }
        var year by rememberSaveable { mutableStateOf(track.year?.toString() ?: "") }
        var trackNumber by rememberSaveable { mutableStateOf(track.trackNumber?.toString() ?: "") }
        var discNumber by rememberSaveable { mutableStateOf(track.discNumber?.toString() ?: "") }
        var comment by rememberSaveable { mutableStateOf(track.comment ?: "") }
        var lyrics by rememberSaveable { mutableStateOf(track.unsyncedLyrics ?: "") }

        var isSaving by remember { mutableStateOf(false) }

        fun handleSaveResult(result: SaveTagsResult) {
            isSaving = false
            when (result) {
                is SaveTagsResult.Success -> {
                    uiManager.toast(Strings[R.string.toast_track_tags_saved])
                    viewModel.scanLibrary(true)
                    uiManager.closeTopLevelScreen(this@EditTagsScreen)
                }
                is SaveTagsResult.Error -> {
                    uiManager.toast(
                        Strings[R.string.toast_track_tags_save_failed].icuFormat(result.message)
                    )
                }
            }
        }

        val writeResultLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                coroutineScope.launch {
                    val saveResult = withContext(Dispatchers.IO) {
                        saveTags(
                            track.path,
                            title,
                            artist,
                            album,
                            albumArtist,
                            genre,
                            year,
                            trackNumber,
                            discNumber,
                            comment,
                            lyrics
                        )
                    }
                    handleSaveResult(saveResult)
                }
            } else {
                isSaving = false
                uiManager.toast(
                    Strings[R.string.toast_track_tags_save_failed].icuFormat("Permission denied")
                )
            }
        }

        fun saveTagsWithPermission() {
            if (isSaving) return
            isSaving = true

            coroutineScope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ uses MediaStore.createWriteRequest
                    val intentSender = MediaStore.createWriteRequest(
                        context.contentResolver,
                        listOf(track.uri)
                    ).intentSender
                    writeResultLauncher.launch(
                        IntentSenderRequest.Builder(intentSender).build()
                    )
                } else {
                    // For older Android versions, try direct write
                    val saveResult = withContext(Dispatchers.IO) {
                        saveTags(
                            track.path,
                            title,
                            artist,
                            album,
                            albumArtist,
                            genre,
                            year,
                            trackNumber,
                            discNumber,
                            comment,
                            lyrics
                        )
                    }
                    handleSaveResult(saveResult)
                }
            }
        }

        // Check if format is supported
        val isSupportedFormat = remember(track.path) {
            val extension = FilenameUtils.getExtension(track.path).lowercase()
            extension in SUPPORTED_TAG_FORMATS
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            Strings[R.string.track_edit_tags_title],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { uiManager.back() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = Strings[R.string.commons_back],
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { saveTagsWithPermission() },
                            enabled = isSupportedFormat && !isSaving
                        ) {
                            Icon(
                                Icons.Filled.Save,
                                contentDescription = Strings[R.string.track_edit_tags_save],
                            )
                        }
                    },
                )
            }
        ) { scaffoldPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp)
                ) {
                    if (!isSupportedFormat) {
                        Text(
                            Strings[R.string.track_edit_tags_unsupported_format],
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(Strings[R.string.track_edit_tags_title_field]) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isSupportedFormat,
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = artist,
                        onValueChange = { artist = it },
                        label = { Text(Strings[R.string.track_edit_tags_artist_field]) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isSupportedFormat,
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = album,
                        onValueChange = { album = it },
                        label = { Text(Strings[R.string.track_edit_tags_album_field]) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isSupportedFormat,
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = albumArtist,
                        onValueChange = { albumArtist = it },
                        label = { Text(Strings[R.string.track_edit_tags_album_artist_field]) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isSupportedFormat,
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = genre,
                        onValueChange = { genre = it },
                        label = { Text(Strings[R.string.track_edit_tags_genre_field]) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isSupportedFormat,
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = year,
                        onValueChange = { year = it },
                        label = { Text(Strings[R.string.track_edit_tags_year_field]) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isSupportedFormat,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = trackNumber,
                        onValueChange = { trackNumber = it },
                        label = { Text(Strings[R.string.track_edit_tags_track_number_field]) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isSupportedFormat,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = discNumber,
                        onValueChange = { discNumber = it },
                        label = { Text(Strings[R.string.track_edit_tags_disc_number_field]) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isSupportedFormat,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text(Strings[R.string.track_edit_tags_comment_field]) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isSupportedFormat,
                        minLines = 2,
                        maxLines = 4,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = lyrics,
                        onValueChange = { lyrics = it },
                        label = { Text(Strings[R.string.track_edit_tags_lyrics_field]) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isSupportedFormat,
                        minLines = 4,
                        maxLines = 10,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

private fun saveTags(
    path: String,
    title: String,
    artist: String,
    album: String,
    albumArtist: String,
    genre: String,
    year: String,
    trackNumber: String,
    discNumber: String,
    comment: String,
    lyrics: String
): SaveTagsResult {
    return try {
        val file = File(path)
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tagOrCreateAndSetDefault

        // Helper function to safely set or delete a field
        fun setOrDeleteField(key: FieldKey, value: String) {
            if (value.isNotBlank()) {
                tag.setField(key, value.trim())
            } else {
                // Deleting a field that doesn't exist may throw in some formats
                // This is expected behavior and can be safely ignored
                try {
                    tag.deleteField(key)
                } catch (e: Exception) {
                    Log.d("EditTagsScreen", "Could not delete field $key: ${e.message}")
                }
            }
        }

        // Helper function to set or delete numeric fields with validation
        fun setOrDeleteNumericField(key: FieldKey, value: String) {
            val trimmedValue = value.trim()
            if (trimmedValue.isNotBlank()) {
                // Validate that the value is a valid integer
                val numericValue = trimmedValue.toIntOrNull()
                if (numericValue != null && numericValue >= 0) {
                    tag.setField(key, trimmedValue)
                } else if (trimmedValue.isNotEmpty()) {
                    // If it's not a valid number but not empty, still try to set it
                    // JAudioTagger will handle format-specific validation
                    tag.setField(key, trimmedValue)
                }
            } else {
                try {
                    tag.deleteField(key)
                } catch (e: Exception) {
                    Log.d("EditTagsScreen", "Could not delete field $key: ${e.message}")
                }
            }
        }

        setOrDeleteField(FieldKey.TITLE, title)
        setOrDeleteField(FieldKey.ARTIST, artist)
        setOrDeleteField(FieldKey.ALBUM, album)
        setOrDeleteField(FieldKey.ALBUM_ARTIST, albumArtist)
        setOrDeleteField(FieldKey.GENRE, genre)
        setOrDeleteNumericField(FieldKey.YEAR, year)
        setOrDeleteNumericField(FieldKey.TRACK, trackNumber)
        setOrDeleteNumericField(FieldKey.DISC_NO, discNumber)
        setOrDeleteField(FieldKey.COMMENT, comment)
        setOrDeleteField(FieldKey.LYRICS, lyrics)

        audioFile.commit()
        SaveTagsResult.Success
    } catch (e: Exception) {
        Log.e("EditTagsScreen", "Error saving tags for $path", e)
        SaveTagsResult.Error(e.message ?: "Unknown error")
    }
}
