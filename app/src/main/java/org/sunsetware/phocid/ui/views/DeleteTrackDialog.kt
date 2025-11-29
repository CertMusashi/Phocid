package org.sunsetware.phocid.ui.views

import android.app.Activity
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sunsetware.phocid.Dialog
import org.sunsetware.phocid.MainViewModel
import org.sunsetware.phocid.R
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.globals.Strings
import org.sunsetware.phocid.ui.components.DialogBase
import org.sunsetware.phocid.utils.icuFormat

@Stable
class DeleteTrackDialog(private val tracks: List<Track>) : Dialog() {
    @Composable
    override fun Compose(viewModel: MainViewModel) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val uris = remember { tracks.map { it.uri } }
        val trackCount = tracks.size
        val singleTrackTitle = rememberSaveable {
            tracks.firstOrNull()?.displayTitle ?: ""
        }

        val deleteResultLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.uiManager.toast(
                    Strings[R.string.toast_track_deleted].icuFormat(trackCount)
                )
                // Trigger a library rescan to refresh the track list
                viewModel.scanLibrary(true)
            } else {
                viewModel.uiManager.toast(
                    Strings[R.string.toast_track_delete_failed].icuFormat(trackCount)
                )
            }
            viewModel.uiManager.closeDialog()
        }

        DialogBase(
            title = if (trackCount == 1) {
                Strings[R.string.track_delete_single_dialog_title]
            } else {
                Strings[R.string.track_delete_multiple_dialog_title].icuFormat(trackCount)
            },
            onConfirm = {
                coroutineScope.launch {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // Android 11+ uses MediaStore.createDeleteRequest
                        val intentSender = MediaStore.createDeleteRequest(
                            context.contentResolver,
                            uris
                        ).intentSender
                        deleteResultLauncher.launch(
                            IntentSenderRequest.Builder(intentSender).build()
                        )
                    } else {
                        // For older Android versions, try direct deletion
                        val success = withContext(Dispatchers.IO) {
                            deleteTracksLegacy(context.contentResolver, uris)
                        }
                        if (success) {
                            viewModel.uiManager.toast(
                                Strings[R.string.toast_track_deleted].icuFormat(trackCount)
                            )
                            viewModel.scanLibrary(true)
                        } else {
                            viewModel.uiManager.toast(
                                Strings[R.string.toast_track_delete_failed].icuFormat(trackCount)
                            )
                        }
                        viewModel.uiManager.closeDialog()
                    }
                }
            },
            onDismiss = { viewModel.uiManager.closeDialog() },
        ) {
            Text(
                if (trackCount == 1) {
                    Strings[R.string.track_delete_single_dialog_body].icuFormat(singleTrackTitle)
                } else {
                    Strings[R.string.track_delete_multiple_dialog_body].icuFormat(trackCount)
                },
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

private fun deleteTracksLegacy(contentResolver: ContentResolver, uris: List<Uri>): Boolean {
    var allSucceeded = true
    for (uri in uris) {
        try {
            val rowsDeleted = contentResolver.delete(uri, null, null)
            if (rowsDeleted == 0) {
                allSucceeded = false
            }
        } catch (e: Exception) {
            allSucceeded = false
        }
    }
    return allSucceeded
}
