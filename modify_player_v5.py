import re

file_path = r"C:\Users\akash\AndroidStudioProjects\M3uVideoPlayer\app\src\main\java\com\mdaksh\m3uvideoplayer\ui\player\PlayerActivity.kt"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Clean up prepareQualityVariants
prepare_old = """        // A fresh item resets Auto mode and all of the observer's rolling counters.
        attemptedQualityIndices.clear()
        recentBufferingStalls = 0
        awaitingFirstFrame = false
        smoothPlaybackSince = 0L
        lastAutoUpgradeAt = 0L
        qualitySwapInFlight = false
        handler.removeCallbacks(handshakeTimeoutRunnable)
        handler.removeCallbacks(clearBufferingStallsRunnable)"""
prepare_new = """        // A fresh item resets attempted indices.
        attemptedQualityIndices.clear()
        attemptedQualityIndices.add(currentQualityIndex)"""
content = content.replace(prepare_old, prepare_new)

# Also fix the double add in prepareQualityVariants from earlier
content = content.replace(
"""        currentQualityIndex = QualityUrlParser.detectedIndex(cleanUrl, qualityVariants)
        attemptedQualityIndices.add(currentQualityIndex)

        // A fresh item resets attempted indices.
        attemptedQualityIndices.clear()
        attemptedQualityIndices.add(currentQualityIndex)""",
"""        currentQualityIndex = QualityUrlParser.detectedIndex(cleanUrl, qualityVariants)

        // A fresh item resets attempted indices.
        attemptedQualityIndices.clear()
        attemptedQualityIndices.add(currentQualityIndex)""")

# 2. Rewrite showQualityDialog
dialog_old_regex = r"    private fun showQualityDialog\(\) \{[\s\S]*?\.show\(\)\s*\n    \}"
dialog_new = """    private fun showQualityDialog() {
        if (!hasQualityOptions) return
        handler.removeCallbacks(hideControlsRunnable)

        val labels = qualityVariants.map { it.label }.toTypedArray()
        val checked = currentQualityIndex

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_quality)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                selectQualityManually(which)
                dialog.dismiss()
            }
            .setOnDismissListener { scheduleHideControls() }
            .show()
    }"""
content = re.sub(dialog_old_regex, dialog_new, content)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Done PlayerActivity.kt v5")
