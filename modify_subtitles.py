import re

# 1. PlaybackEngine.kt
file_path = r"C:\Users\akash\AndroidStudioProjects\M3uVideoPlayer\app\src\main\java\com\mdaksh\m3uvideoplayer\ui\player\engine\PlaybackEngine.kt"
with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()
if "fun clearSubtitleTrack()" not in content:
    content = content.replace("    fun selectSubtitleTrack(id: String)", "    fun selectSubtitleTrack(id: String)\n    fun clearSubtitleTrack()")
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)

# 2. EngineController.kt
file_path = r"C:\Users\akash\AndroidStudioProjects\M3uVideoPlayer\app\src\main\java\com\mdaksh\m3uvideoplayer\ui\player\engine\EngineController.kt"
with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()
if "fun clearSubtitleTrack()" not in content:
    content = content.replace("    fun selectSubtitleTrack(id: String) = active.selectSubtitleTrack(id)", "    fun selectSubtitleTrack(id: String) = active.selectSubtitleTrack(id)\n    fun clearSubtitleTrack() = active.clearSubtitleTrack()")
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)

# 3. ExoPlaybackEngine.kt
file_path = r"C:\Users\akash\AndroidStudioProjects\M3uVideoPlayer\app\src\main\java\com\mdaksh\m3uvideoplayer\ui\player\engine\ExoPlaybackEngine.kt"
with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()
if "override fun clearSubtitleTrack()" not in content:
    new_method = """    override fun clearSubtitleTrack() {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun selectTrack(id: String, type: Int) {"""
    content = content.replace("    private fun selectTrack(id: String, type: Int) {", new_method)
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)

# 4. VlcPlaybackEngine.kt
file_path = r"C:\Users\akash\AndroidStudioProjects\M3uVideoPlayer\app\src\main\java\com\mdaksh\m3uvideoplayer\ui\player\engine\VlcPlaybackEngine.kt"
with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()
if "override fun clearSubtitleTrack()" not in content:
    content = content.replace("    override fun selectSubtitleTrack(id: String) { id.toIntOrNull()?.let { player?.spuTrack = it } }", "    override fun selectSubtitleTrack(id: String) { id.toIntOrNull()?.let { player?.spuTrack = it } }\n    override fun clearSubtitleTrack() { player?.spuTrack = -1 }")
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)

# 5. PlayerActivity.kt
file_path = r"C:\Users\akash\AndroidStudioProjects\M3uVideoPlayer\app\src\main\java\com\mdaksh\m3uvideoplayer\ui\player\PlayerActivity.kt"
with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

old_showTrackDialog = """    private fun showTrackDialog(audio: Boolean) {
        val player = engine ?: return
        handler.removeCallbacks(hideControlsRunnable)
        val tracks = if (audio) player.audioTracks() else player.subtitleTracks()
        if (tracks.isEmpty()) {
            Toast.makeText(
                this,
                if (audio) R.string.player_no_audio_tracks else R.string.player_no_subtitle_tracks,
                Toast.LENGTH_SHORT
            ).show()
            scheduleHideControls()
            return
        }
        val names = tracks.map { it.label }.toTypedArray()
        val checked = tracks.indexOfFirst { it.selected }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (audio) R.string.player_audio_track else R.string.player_subtitle_track)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val id = tracks[which].id
                if (audio) player.selectAudioTrack(id) else player.selectSubtitleTrack(id)
                showIndicator(
                    getString(
                        if (audio) R.string.player_osd_audio_selected
                        else R.string.player_osd_subtitle_selected,
                        tracks[which].label
                    )
                )
                dialog.dismiss()
            }
            .setOnDismissListener { scheduleHideControls() }
            .show()
    }"""

new_showTrackDialog = """    private fun showTrackDialog(audio: Boolean) {
        val player = engine ?: return
        handler.removeCallbacks(hideControlsRunnable)
        val tracks = if (audio) player.audioTracks() else player.subtitleTracks()
        if (tracks.isEmpty()) {
            Toast.makeText(
                this,
                if (audio) R.string.player_no_audio_tracks else R.string.player_no_subtitle_tracks,
                Toast.LENGTH_SHORT
            ).show()
            scheduleHideControls()
            return
        }

        val names = if (audio) {
            tracks.map { it.label }.toTypedArray()
        } else {
            (listOf(getString(R.string.player_track_none)) + tracks.map { it.label }).toTypedArray()
        }
        
        val checked = if (audio) {
            tracks.indexOfFirst { it.selected }
        } else {
            val idx = tracks.indexOfFirst { it.selected }
            if (idx == -1) 0 else idx + 1 // 0 is "None"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (audio) R.string.player_audio_track else R.string.player_subtitle_track)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                if (audio) {
                    val id = tracks[which].id
                    player.selectAudioTrack(id)
                    showIndicator(getString(R.string.player_osd_audio_selected, tracks[which].label))
                } else {
                    if (which == 0) {
                        player.clearSubtitleTrack()
                        showIndicator(getString(R.string.player_osd_subtitle_selected, getString(R.string.player_track_none)))
                    } else {
                        val id = tracks[which - 1].id
                        player.selectSubtitleTrack(id)
                        showIndicator(getString(R.string.player_osd_subtitle_selected, tracks[which - 1].label))
                    }
                }
                dialog.dismiss()
            }
            .setOnDismissListener { scheduleHideControls() }
            .show()
    }"""

content = content.replace(old_showTrackDialog, new_showTrackDialog)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Done subtitles fix")
