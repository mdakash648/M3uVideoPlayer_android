import re
import sys

file_path = r"C:\Users\akash\AndroidStudioProjects\M3uVideoPlayer\app\src\main\java\com\mdaksh\m3uvideoplayer\ui\player\PlayerActivity.kt"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# Remove autoQualityEnabled and related fields
content = re.sub(r"^\s*/\*\* Item D.*?\n\s*private var autoQualityEnabled = true\n", "", content, flags=re.MULTILINE)
content = re.sub(r"^\s*private var recentBufferingStalls = 0\n", "", content, flags=re.MULTILINE)
content = re.sub(r"^\s*private var smoothPlaybackSince = 0L\n", "", content, flags=re.MULTILINE)
content = re.sub(r"^\s*private var lastAutoUpgradeAt = 0L\n", "", content, flags=re.MULTILINE)
content = re.sub(r"^\s*private var qualitySwapInFlight = false\n", "", content, flags=re.MULTILINE)
content = re.sub(r"^\s*private var awaitingFirstFrame = false\n", "", content, flags=re.MULTILINE)
content = re.sub(r"^\s*private var qualityLoadStartedAt = 0L\n", "", content, flags=re.MULTILINE)
content = re.sub(r"^\s*private val handshakeTimeoutRunnable = Runnable \{ onSlowHandshake\(\) \}\n", "", content, flags=re.MULTILINE)
content = re.sub(r"^\s*private val clearBufferingStallsRunnable = Runnable \{ recentBufferingStalls = 0 \}\n", "", content, flags=re.MULTILINE)

# Add attemptedQualityIndices
content = re.sub(r"(private var currentQualityIndex = 0)", r"\1\n    private var attemptedQualityIndices = mutableSetOf<Int>()", content)

# Modify prepareQualityVariants
prepare_old = """    private fun prepareQualityVariants() {
        if (qualityVariants.isNotEmpty()) return  // generated once per playback attempt
        val cleanUrl = StreamUrl.parse(currentQueueItem?.url.orEmpty()).url
        if (cleanUrl.isBlank()) return
        qualityVariants = QualityUrlParser.variants(cleanUrl)
        currentQualityIndex = QualityUrlParser.detectedIndex(cleanUrl, qualityVariants)
    }"""
prepare_new = """    private fun prepareQualityVariants() {
        if (qualityVariants.isNotEmpty()) return  // generated once per playback attempt
        val cleanUrl = StreamUrl.parse(currentQueueItem?.url.orEmpty()).url
        if (cleanUrl.isBlank()) return
        qualityVariants = QualityUrlParser.variants(cleanUrl)
        currentQualityIndex = QualityUrlParser.detectedIndex(cleanUrl, qualityVariants)
        attemptedQualityIndices.add(currentQualityIndex)
    }"""
content = content.replace(prepare_old, prepare_new)

# Modify tryQualityFallback
fallback_old = """    private fun tryQualityFallback(): Boolean {
        if (!hasQualityOptions) return false
        val next = currentQualityIndex + 1
        val lower = qualityVariants.getOrNull(next) ?: return false  // already at the lowest rung
        val from = qualityVariants[currentQualityIndex].label
        switchQuality(next, getString(R.string.player_quality_fallback, from, lower.label))
        return true
    }"""
fallback_new = """    private fun tryQualityFallback(): Boolean {
        if (!hasQualityOptions) return false
        val next = (0 until qualityVariants.size).firstOrNull { it !in attemptedQualityIndices }
            ?: return false
        val from = qualityVariants[currentQualityIndex].label
        val lower = qualityVariants[next]
        switchQuality(next, getString(R.string.player_quality_fallback, from, lower.label))
        return true
    }"""
content = content.replace(fallback_old, fallback_new)

# Update switchQuality to add to attemptedQualityIndices
switch_old = """        currentQualityIndex = targetIndex
        qualitySwapInFlight = true
        announce?.let { showIndicator(it) }"""
switch_new = """        currentQualityIndex = targetIndex
        attemptedQualityIndices.add(targetIndex)
        announce?.let { showIndicator(it) }"""
content = content.replace(switch_old, switch_new)

# Modify playItem to clear attemptedQualityIndices (actually qualityVariants is cleared there, but we should clear attemptedQualityIndices too)
play_item_old = """        qualityVariants = emptyList()
        currentQualityIndex = 0
        autoQualityEnabled = true"""
play_item_new = """        qualityVariants = emptyList()
        currentQualityIndex = 0
        attemptedQualityIndices.clear()"""
content = content.replace(play_item_old, play_item_new)

# Remove auto quality functions
# We can just remove the bodies of these functions or remove them completely
functions_to_remove = [
    r"^\s*/\*\* Item D — user chose Auto:.*?\n\s*private fun enableAutoQuality\(\) \{.*?\n\s*\}\n",
    r"^\s*/\*\*[\s\S]*?private fun armQualityLoadTimers\(\) \{[\s\S]*?\n\s*\}\n",
    r"^\s*/\*\*[\s\S]*?private fun onSlowHandshake\(\) \{[\s\S]*?\n\s*\}\n",
    r"^\s*/\*\*[\s\S]*?private fun downgradeToLowestQuality\(\) \{[\s\S]*?\n\s*\}\n",
    r"^\s*/\*\*[\s\S]*?private fun maybeUpgradeQuality\(\) \{[\s\S]*?\n\s*\}\n"
]
for p in functions_to_remove:
    content = re.sub(p, "", content, flags=re.MULTILINE)

# showQualityDialog modifications
show_dialog_old = """        val autoLabel = getString(R.string.player_quality_auto)
        val current = qualityVariants.getOrNull(currentQualityIndex)?.label ?: qualityVariants.first().label
        val labels = (listOf(autoLabel) + qualityVariants.map { it.label }).toTypedArray()
        val checkedItem = if (autoQualityEnabled) 0 else currentQualityIndex + 1

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.player_quality))
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                if (which == 0) enableAutoQuality() else selectQualityManually(which - 1)
            }"""
show_dialog_new = """        val labels = qualityVariants.map { it.label }.toTypedArray()
        val checkedItem = currentQualityIndex

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.player_quality))
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                selectQualityManually(which)
            }"""
content = content.replace(show_dialog_old, show_dialog_new)

# In selectQualityManually, remove autoQualityEnabled = false
content = re.sub(r"^\s*autoQualityEnabled = false\n", "", content, flags=re.MULTILINE)

# engineListener onBuffering removal
buffering_old = """        override fun onBuffering() {
            showLoading()
            // promt2 Item D — count buffering stalls that happen AFTER the first frame (a mid-play
            // stall, not the initial connect). Repeated stalls in a short window = weak network =>
            // downgrade to the lowest rung. A clean window resets the counter.
            if (autoQualityEnabled && hasQualityOptions && !awaitingFirstFrame && !qualitySwapInFlight) {
                recentBufferingStalls++
                smoothPlaybackSince = 0L  // playback is no longer smooth; upgrade window restarts
                handler.removeCallbacks(clearBufferingStallsRunnable)
                handler.postDelayed(clearBufferingStallsRunnable, QUALITY_STALL_WINDOW_MS)
                if (recentBufferingStalls >= QUALITY_STALL_DOWNGRADE_COUNT) {
                    handler.removeCallbacks(clearBufferingStallsRunnable)
                    downgradeToLowestQuality()
                }
            }
        }"""
buffering_new = """        override fun onBuffering() {
            showLoading()
        }"""
content = content.replace(buffering_old, buffering_new)

# engineListener onReady removal of auto timers
ready_old = """        override fun onReady() {
            showVideo()
            // promt2 Item D — first frame rendered: disarm the handshake stopwatch and open the
            // smooth-playback upgrade window. A completed quality/fallback swap is now settled.
            handler.removeCallbacks(handshakeTimeoutRunnable)
            awaitingFirstFrame = false
            qualitySwapInFlight = false
            if (smoothPlaybackSince == 0L) smoothPlaybackSince = SystemClock.elapsedRealtime()
            // Re-apply user preferences the fresh load reset.
            applyVolume()"""
ready_new = """        override fun onReady() {
            showVideo()
            // Re-apply user preferences the fresh load reset.
            applyVolume()"""
content = content.replace(ready_old, ready_new)

# progressTicker maybeUpgradeQuality removal
content = re.sub(r"^\s*maybeUpgradeQuality\(\)\n", "", content, flags=re.MULTILINE)

# remove armQualityLoadTimers() calls
content = re.sub(r"^\s*armQualityLoadTimers\(\)\n", "", content, flags=re.MULTILINE)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Done PlayerActivity.kt")
