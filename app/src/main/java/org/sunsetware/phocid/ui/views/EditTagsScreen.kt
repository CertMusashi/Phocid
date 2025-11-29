@file:OptIn(ExperimentalMaterial3Api::class)

package org.sunsetware.phocid.ui.views

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
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

        val writeResultLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                coroutineScope.launch {
                    val success = withContext(Dispatchers.IO) {
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
                    isSaving = false
                    if (success) {
                        uiManager.toast(Strings[R.string.toast_track_tags_saved])
                        viewModel.scanLibrary(true)
                        uiManager.closeTopLevelScreen(this@EditTagsScreen)
                    } else {
                        uiManager.toast(
                            Strings[R.string.toast_track_tags_save_failed].icuFormat("Unknown error")
                        )
                    }
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
                    val success = withContext(Dispatchers.IO) {
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
                    isSaving = false
                    if (success) {
                        uiManager.toast(Strings[R.string.toast_track_tags_saved])
                        viewModel.scanLibrary(true)
                        uiManager.closeTopLevelScreen(this@EditTagsScreen)
                    } else {
                        uiManager.toast(
                            Strings[R.string.toast_track_tags_save_failed].icuFormat("Unknown error")
                        )
                    }
                }
            }
        }

        // Check if format is supported
        val isSupportedFormat = remember(track.path) {
            val extension = FilenameUtils.getExtension(track.path).lowercase()
            extension in listOf("mp3", "flac", "ogg", "m4a", "mp4", "wma", "wav", "aif", "aiff", "dsf")
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
): Boolean {
    return try {
        val file = File(path)
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tagOrCreateAndSetDefault

        // Set or delete each field based on whether it's empty
        if (title.isNotBlank()) {
            tag.setField(FieldKey.TITLE, title.trim())
        } else {
            try { tag.deleteField(FieldKey.TITLE) } catch (_: Exception) {}
        }

        if (artist.isNotBlank()) {
            tag.setField(FieldKey.ARTIST, artist.trim())
        } else {
            try { tag.deleteField(FieldKey.ARTIST) } catch (_: Exception) {}
        }

        if (album.isNotBlank()) {
            tag.setField(FieldKey.ALBUM, album.trim())
        } else {
            try { tag.deleteField(FieldKey.ALBUM) } catch (_: Exception) {}
        }

        if (albumArtist.isNotBlank()) {
            tag.setField(FieldKey.ALBUM_ARTIST, albumArtist.trim())
        } else {
            try { tag.deleteField(FieldKey.ALBUM_ARTIST) } catch (_: Exception) {}
        }

        if (genre.isNotBlank()) {
            tag.setField(FieldKey.GENRE, genre.trim())
        } else {
            try { tag.deleteField(FieldKey.GENRE) } catch (_: Exception) {}
        }

        if (year.isNotBlank()) {
            tag.setField(FieldKey.YEAR, year.trim())
        } else {
            try { tag.deleteField(FieldKey.YEAR) } catch (_: Exception) {}
        }

        if (trackNumber.isNotBlank()) {
            tag.setField(FieldKey.TRACK, trackNumber.trim())
        } else {
            try { tag.deleteField(FieldKey.TRACK) } catch (_: Exception) {}
        }

        if (discNumber.isNotBlank()) {
            tag.setField(FieldKey.DISC_NO, discNumber.trim())
        } else {
            try { tag.deleteField(FieldKey.DISC_NO) } catch (_: Exception) {}
        }

        if (comment.isNotBlank()) {
            tag.setField(FieldKey.COMMENT, comment.trim())
        } else {
            try { tag.deleteField(FieldKey.COMMENT) } catch (_: Exception) {}
        }

        if (lyrics.isNotBlank()) {
            tag.setField(FieldKey.LYRICS, lyrics.trim())
        } else {
            try { tag.deleteField(FieldKey.LYRICS) } catch (_: Exception) {}
        }

        audioFile.commit()
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
