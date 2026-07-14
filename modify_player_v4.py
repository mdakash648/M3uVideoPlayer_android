import re

file_path = r"C:\Users\akash\AndroidStudioProjects\M3uVideoPlayer\app\src\main\java\com\mdaksh\m3uvideoplayer\ui\player\PlayerActivity.kt"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# exact replacements for the class fields (commenting them out instead of deleting)
fields = [
    "private var autoQualityEnabled: Boolean = true",
    "private var qualityLoadStartedAt: Long = 0L",
    "private var awaitingFirstFrame: Boolean = false",
    "private var recentBufferingStalls: Int = 0",
    "private var lastAutoUpgradeAt: Long = 0L",
    "private var smoothPlaybackSince: Long = 0L",
    "private var qualitySwapInFlight: Boolean = false",
    "private val clearBufferingStallsRunnable = Runnable { recentBufferingStalls = 0 }",
    """private val handshakeTimeoutRunnable = Runnable {
        if (awaitingFirstFrame && autoQualityEnabled && hasQualityOptions) {
            onSlowHandshake()
        }
    }"""
]
for field in fields:
    content = content.replace(field, "/* " + field.replace("/*", "").replace("*/", "") + " */")

# Add attemptedQualityIndices
content = content.replace("private var currentQualityIndex: Int = 0", "private var currentQualityIndex: Int = 0\n    private var attemptedQualityIndices = mutableSetOf<Int>()")

# Modify prepareQualityVariants
content = content.replace(
    "currentQualityIndex = QualityUrlParser.detectedIndex(cleanUrl, qualityVariants)",
    "currentQualityIndex = QualityUrlParser.detectedIndex(cleanUrl, qualityVariants)\n        attemptedQualityIndices.add(currentQualityIndex)"
)

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

# Update switchQuality
content = content.replace(
    "qualitySwapInFlight = true",
    "attemptedQualityIndices.add(targetIndex)"
)

# Update playItem
content = content.replace(
    "autoQualityEnabled = true",
    "attemptedQualityIndices.clear()"
)

# Update showQualityDialog
dialog_old = """        val autoLabel = getString(R.string.player_quality_auto)
        val current = qualityVariants.getOrNull(currentQualityIndex)?.label ?: qualityVariants.first().label
        val labels = (listOf(autoLabel) + qualityVariants.map { it.label }).toTypedArray()
        val checkedItem = if (autoQualityEnabled) 0 else currentQualityIndex + 1

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.player_quality))
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                if (which == 0) enableAutoQuality() else selectQualityManually(which - 1)
            }"""
dialog_new = """        val labels = qualityVariants.map { it.label }.toTypedArray()
        val checkedItem = currentQualityIndex

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.player_quality))
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                selectQualityManually(which)
            }"""
content = content.replace(dialog_old, dialog_new)

# Remove autoQualityEnabled = false from selectQualityManually
content = content.replace("        autoQualityEnabled = false\n", "")

# engineListener onBuffering
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

# engineListener onReady
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

# remove maybeUpgradeQuality() from progressTicker
content = content.replace("            maybeUpgradeQuality()\n", "")

# remove armQualityLoadTimers() calls
content = content.replace("        armQualityLoadTimers()\n", "")

# Empty the bodies of unused functions instead of deleting them to avoid brace mismatch
def empty_body(func_name, content):
    pattern = r"(private fun " + func_name + r"\(\)[^{]*\{)([\s\S]*?)(\n    \})"
    return re.sub(pattern, r"\1\3", content)

for fn in ["enableAutoQuality", "armQualityLoadTimers", "onSlowHandshake", "downgradeToLowestQuality", "maybeUpgradeQuality"]:
    content = empty_body(fn, content)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Done PlayerActivity.kt v4")
