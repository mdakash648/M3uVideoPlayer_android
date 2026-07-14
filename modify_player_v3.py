import re
import os

file_path = r"C:\Users\akash\AndroidStudioProjects\M3uVideoPlayer\app\src\main\java\com\mdaksh\m3uvideoplayer\ui\player\PlayerActivity.kt"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Remove properties (using regex for flexibility)
props = [
    r"^\s*/\*\*.*?Item D — true while.*?autoQualityEnabled.*\n",
    r"^\s*private var autoQualityEnabled.*?\n",
    r"^\s*/\*\*.*?qualityLoadStartedAt.*\n",
    r"^\s*private var qualityLoadStartedAt.*?\n",
    r"^\s*/\*\*.*?awaitingFirstFrame.*\n",
    r"^\s*private var awaitingFirstFrame.*?\n",
    r"^\s*/\*\*.*?recentBufferingStalls.*\n",
    r"^\s*private var recentBufferingStalls.*?\n",
    r"^\s*/\*\*.*?lastAutoUpgradeAt.*\n",
    r"^\s*private var lastAutoUpgradeAt.*?\n",
    r"^\s*/\*\*.*?smoothPlaybackSince.*\n",
    r"^\s*private var smoothPlaybackSince.*?\n",
    r"^\s*/\*\*.*?qualitySwapInFlight.*\n",
    r"^\s*private var qualitySwapInFlight.*?\n",
    r"^\s*/\*\*.*?Item D — resets the stall counter.*?\n",
    r"^\s*private val clearBufferingStallsRunnable.*?\n",
    r"^\s*/\*\*.*?Item D — fires if the current variant's handshake.*?\n",
    r"^\s*private val handshakeTimeoutRunnable = Runnable \{[\s\S]*?\}\n"
]
for p in props:
    content = re.sub(p, "", content, flags=re.MULTILINE)

# 2. Add attemptedQualityIndices
content = re.sub(r"(private var currentQualityIndex: Int = 0)", r"\1\n    private var attemptedQualityIndices = mutableSetOf<Int>()", content)

# 3. Modify prepareQualityVariants
content = re.sub(
    r"currentQualityIndex = QualityUrlParser\.detectedIndex\(cleanUrl, qualityVariants\)",
    r"currentQualityIndex = QualityUrlParser.detectedIndex(cleanUrl, qualityVariants)\n        attemptedQualityIndices.add(currentQualityIndex)",
    content
)

# 4. Modify tryQualityFallback
fallback_old = r"""    private fun tryQualityFallback\(\): Boolean \{
        if \(!hasQualityOptions\) return false
        val next = currentQualityIndex \+ 1
        val lower = qualityVariants\.getOrNull\(next\) \?: return false  // already at the lowest rung
        val from = qualityVariants\[currentQualityIndex\]\.label
        switchQuality\(next, getString\(R\.string\.player_quality_fallback, from, lower\.label\)\)
        return true
    \}"""
fallback_new = """    private fun tryQualityFallback(): Boolean {
        if (!hasQualityOptions) return false
        val next = (0 until qualityVariants.size).firstOrNull { it !in attemptedQualityIndices }
            ?: return false
        val from = qualityVariants[currentQualityIndex].label
        val lower = qualityVariants[next]
        switchQuality(next, getString(R.string.player_quality_fallback, from, lower.label))
        return true
    }"""
content = re.sub(fallback_old, fallback_new, content)

# 5. Update switchQuality
content = re.sub(
    r"currentQualityIndex = targetIndex\s*\n\s*qualitySwapInFlight = true",
    r"currentQualityIndex = targetIndex\n        attemptedQualityIndices.add(targetIndex)",
    content
)

# 6. Update playItem
content = re.sub(
    r"currentQualityIndex = 0\s*\n\s*autoQualityEnabled = true",
    r"currentQualityIndex = 0\n        attemptedQualityIndices.clear()",
    content
)

# 7. Update showQualityDialog
dialog_old = r"""        val autoLabel = getString\(R\.string\.player_quality_auto\)
        val current = qualityVariants\.getOrNull\(currentQualityIndex\)\?\.label \?: qualityVariants\.first\(\)\.label
        val labels = \(listOf\(autoLabel\) \+ qualityVariants\.map \{ it\.label \}\)\.toTypedArray\(\)
        val checkedItem = if \(autoQualityEnabled\) 0 else currentQualityIndex \+ 1

        AlertDialog\.Builder\(this\)
            \.setTitle\(getString\(R\.string\.player_quality\)\)
            \.setSingleChoiceItems\(labels, checkedItem\) \{ dialog, which ->
                dialog\.dismiss\(\)
                if \(which == 0\) enableAutoQuality\(\) else selectQualityManually\(which - 1\)
            \}"""
dialog_new = """        val labels = qualityVariants.map { it.label }.toTypedArray()
        val checkedItem = currentQualityIndex

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.player_quality))
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                selectQualityManually(which)
            }"""
content = re.sub(dialog_old, dialog_new, content)

# 8. Remove autoQualityEnabled = false from selectQualityManually
content = re.sub(r"^\s*autoQualityEnabled = false\n", "", content, flags=re.MULTILINE)

# 9. engineListener onBuffering
content = re.sub(
    r"        override fun onBuffering\(\) \{\s*\n\s*showLoading\(\)\s*\n\s*// promt2 Item D — count buffering stalls[\s\S]*?if \(recentBufferingStalls >= QUALITY_STALL_DOWNGRADE_COUNT\) \{\s*\n\s*handler\.removeCallbacks\(clearBufferingStallsRunnable\)\s*\n\s*downgradeToLowestQuality\(\)\s*\n\s*\}\s*\n\s*\}\s*\n\s*\}",
    r"        override fun onBuffering() {\n            showLoading()\n        }",
    content
)

# 10. engineListener onReady
content = re.sub(
    r"        override fun onReady\(\) \{\s*\n\s*showVideo\(\)\s*\n\s*// promt2 Item D — first frame rendered: disarm the handshake stopwatch[\s\S]*?if \(smoothPlaybackSince == 0L\) smoothPlaybackSince = SystemClock\.elapsedRealtime\(\)",
    r"        override fun onReady() {\n            showVideo()",
    content
)

# 11. progressTicker maybeUpgradeQuality
content = re.sub(r"^\s*maybeUpgradeQuality\(\)\n", "", content, flags=re.MULTILINE)

# 12. armQualityLoadTimers calls
content = re.sub(r"^\s*armQualityLoadTimers\(\)\n", "", content, flags=re.MULTILINE)

# 13. Remove functions
functions_to_remove = [
    r"^\s*/\*\*[\s\S]*?private fun enableAutoQuality\(\) \{[\s\S]*?^\s*\}\n",
    r"^\s*/\*\*[\s\S]*?private fun armQualityLoadTimers\(\) \{[\s\S]*?^\s*\}\n",
    r"^\s*/\*\*[\s\S]*?private fun onSlowHandshake\(\) \{[\s\S]*?^\s*\}\n",
    r"^\s*/\*\*[\s\S]*?private fun downgradeToLowestQuality\(\) \{[\s\S]*?^\s*\}\n",
    r"^\s*/\*\*[\s\S]*?private fun maybeUpgradeQuality\(\) \{[\s\S]*?^\s*\}\n"
]
for p in functions_to_remove:
    content = re.sub(p, "", content, flags=re.MULTILINE)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Done PlayerActivity.kt v3")
