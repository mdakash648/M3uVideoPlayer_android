package com.mdaksh.m3uvideoplayer.ui.settings

import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdaksh.m3uvideoplayer.data.backup.BackupParseException
import com.mdaksh.m3uvideoplayer.data.backup.BackupRestoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** One-shot outcome the Fragment reacts to (shows a Toast/Snackbar for) and then clears. */
sealed interface BackupRestoreEvent {
    object ExportSucceeded : BackupRestoreEvent
    object ImportSucceeded : BackupRestoreEvent
    object DemoLoadSucceeded : BackupRestoreEvent
    data class Failed(val message: String) : BackupRestoreEvent
}

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    private val repository: BackupRestoreRepository
) : ViewModel() {

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _event = MutableStateFlow<BackupRestoreEvent?>(null)
    val event: StateFlow<BackupRestoreEvent?> = _event.asStateFlow()

    /** Consumed by the Fragment right after showing it, so rotations don't replay the same event. */
    fun consumeEvent() {
        _event.value = null
    }

    private companion object {
        const val DEMO_ASSET_NAME = "demo.json"
    }

    /**
     * `m3uvideoPlayer_YYYYMMDD_HHMMSS.json` — the suggested filename handed to the system's
     * "Save As" (`ACTION_CREATE_DOCUMENT`) picker. Computed fresh each time "Save Application
     * Data" is tapped so it always reflects the current moment.
     */
    fun suggestedBackupFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "m3uvideoPlayer_$stamp.json"
    }

    /** Writes the current app state as JSON into the document the user picked via the SAF dialog. */
    fun exportTo(contentResolver: ContentResolver, destination: Uri) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                val json = repository.exportBackupJson()
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(destination)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Couldn't open the destination file")
                }
                _event.value = BackupRestoreEvent.ExportSucceeded
            } catch (e: Exception) {
                _event.value = BackupRestoreEvent.Failed(
                    e.message?.takeIf { it.isNotBlank() } ?: "Couldn't save the backup file."
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    /**
     * promt1 — "Load Demo Data": reads the bundled `assets/demo.json` snapshot and injects it
     * straight into the database (same restore path as a picked backup file), bypassing the SAF
     * file picker. Emits [BackupRestoreEvent.DemoLoadSucceeded] so the Fragment shows the demo
     * toast; the Room-backed flows on the other screens refresh themselves once the tables change.
     */
    fun loadDemoData(assets: AssetManager) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    assets.open(DEMO_ASSET_NAME).use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    }
                }
                repository.importBackupJson(json)
                _event.value = BackupRestoreEvent.DemoLoadSucceeded
            } catch (e: BackupParseException) {
                _event.value = BackupRestoreEvent.Failed(e.message ?: "The demo data isn't valid.")
            } catch (e: Exception) {
                _event.value = BackupRestoreEvent.Failed(
                    e.message?.takeIf { it.isNotBlank() } ?: "Couldn't load the demo data."
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Reads, parses, and restores the JSON backup file the user picked via the SAF dialog. */
    fun importFrom(contentResolver: ContentResolver, source: Uri) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(source)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("Couldn't open the selected file")
                }
                repository.importBackupJson(json)
                _event.value = BackupRestoreEvent.ImportSucceeded
            } catch (e: BackupParseException) {
                _event.value = BackupRestoreEvent.Failed(e.message ?: "This file isn't a valid backup.")
            } catch (e: Exception) {
                _event.value = BackupRestoreEvent.Failed(
                    e.message?.takeIf { it.isNotBlank() } ?: "Couldn't restore this backup file."
                )
            } finally {
                _isBusy.value = false
            }
        }
    }
}
