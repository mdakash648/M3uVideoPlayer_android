import os

file_path = r"C:\Users\akash\AndroidStudioProjects\M3uVideoPlayer\app\src\main\java\com\mdaksh\m3uvideoplayer\ui\player\PlayerActivity.kt"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# exact replacements
replacements = [
    # Remove properties
    ("    private var autoQualityEnabled = true\n", ""),
    ("    private var recentBufferingStalls = 0\n", ""),
    ("    private var smoothPlaybackSince = 0L\n", ""),
    ("    private var lastAutoUpgradeAt = 0L\n", ""),
    ("    private var qualitySwapInFlight = false\n", ""),
    ("    private var awaitingFirstFrame = false\n", ""),
    ("    private var qualityLoadStartedAt = 0L\n", ""),
    ("    private val handshakeTimeoutRunnable = Runnable { onSlowHandshake() }\n", ""),
    ("    private val clearBufferingStallsRunnable = Runnable { recentBufferingStalls = 0 }\n", ""),
    
    # Add attemptedQualityIndices
    ("    private var currentQualityIndex = 0", "    private var currentQualityIndex = 0\n    private var attemptedQualityIndices = mutableSetOf<Int>()"),
    
    # Modify prepareQualityVariants
    ("""    private fun prepareQualityVariants() {
        if (qualityVariants.isNotEmpty()) return  // generated once per playback attempt
        val cleanUrl = StreamUrl.parse(currentQueueItem?.url.orEmpty()).url
        if (cleanUrl.isBlank()) return
        qualityVariants = QualityUrlParser.variants(cleanUrl)
        currentQualityIndex = QualityUrlParser.detectedIndex(cleanUrl, qualityVariants)
    }""",
    """    private fun prepareQualityVariants() {
        if (qualityVariants.isNotEmpty()) return  // generated once per playback attempt
        val cleanUrl = StreamUrl.parse(currentQueueItem?.url.orEmpty()).url
        if (cleanUrl.isBlank()) return
        qualityVariants = QualityUrlParser.variants(cleanUrl)
        currentQualityIndex = QualityUrlParser.detectedIndex(cleanUrl, qualityVariants)
        attemptedQualityIndices.add(currentQualityIndex)
    }"""),
    
    # Modify tryQualityFallback
    ("""    private fun tryQualityFallback(): Boolean {
        if (!hasQualityOptions) return false
        val next = currentQualityIndex + 1
        val lower = qualityVariants.getOrNull(next) ?: return false  // already at the lowest rung
        val from = qualityVariants[currentQualityIndex].label
        switchQuality(next, getString(R.string.player_quality_fallback, from, lower.label))
        return true
    }""",
    """    private fun tryQualityFallback(): Boolean {
        if (!hasQualityOptions) return false
        val next = (0 until qualityVariants.size).firstOrNull { it !in attemptedQualityIndices }
            ?: return false
        val from = qualityVariants[currentQualityIndex].label
        val lower = qualityVariants[next]
        switchQuality(next, getString(R.string.player_quality_fallback, from, lower.label))
        return true
    }"""),
    
    # Update switchQuality
    ("""        currentQualityIndex = targetIndex
        qualitySwapInFlight = true
        announce?.let { showIndicator(it) }""",
    """        currentQualityIndex = targetIndex
        attemptedQualityIndices.add(targetIndex)
        announce?.let { showIndicator(it) }"""),
    
    # Update playItem
    ("""        qualityVariants = emptyList()
        currentQualityIndex = 0
        autoQualityEnabled = true""",
    """        qualityVariants = emptyList()
        currentQualityIndex = 0
        attemptedQualityIndices.clear()"""),

    # Update showQualityDialog
    ("""        val autoLabel = getString(R.string.player_quality_auto)
        val current = qualityVariants.getOrNull(currentQualityIndex)?.label ?: qualityVariants.first().label
        val labels = (listOf(autoLabel) + qualityVariants.map { it.label }).toTypedArray()
        val checkedItem = if (autoQualityEnabled) 0 else currentQualityIndex + 1

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.player_quality))
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                if (which == 0) enableAutoQuality() else selectQualityManually(which - 1)
            }""",
    """        val labels = qualityVariants.map { it.label }.toTypedArray()
        val checkedItem = currentQualityIndex

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.player_quality))
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                selectQualityManually(which)
            }"""),
            
    # Remove autoQualityEnabled = false from selectQualityManually
    ("        autoQualityEnabled = false\n", ""),
    
    # engineListener onBuffering removal
    ("""        override fun onBuffering() {
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
        }""",
    """        override fun onBuffering() {
            showLoading()
        }"""),
        
    # engineListener onReady removal
    ("""        override fun onReady() {
            showVideo()
            // promt2 Item D — first frame rendered: disarm the handshake stopwatch and open the
            // smooth-playback upgrade window. A completed quality/fallback swap is now settled.
            handler.removeCallbacks(handshakeTimeoutRunnable)
            awaitingFirstFrame = false
            qualitySwapInFlight = false
            if (smoothPlaybackSince == 0L) smoothPlaybackSince = SystemClock.elapsedRealtime()
            // Re-apply user preferences the fresh load reset.
            applyVolume()""",
    """        override fun onReady() {
            showVideo()
            // Re-apply user preferences the fresh load reset.
            applyVolume()"""),
            
    # remove maybeUpgradeQuality from progress ticker
    ("            maybeUpgradeQuality()\n", ""),
    
    # remove armQualityLoadTimers
    ("        armQualityLoadTimers()\n", "")
]

for old_str, new_str in replacements:
    if old_str in content:
        content = content.replace(old_str, new_str)
    else:
        print(f"Warning: Could not find exactly:\n{old_str[:100]}...")

# I will also just delete the unused functions by finding their precise signatures and returning early or completely removing them with regex, but very carefully.
import re
functions_to_remove = [
    r"    /\*\* Item D — user chose Auto:.*?\n    private fun enableAutoQuality\(\) \{.*?\n    \}\n",
    r"    /\*\*\n     \* Item D — arm the handshake stopwatch.*?\n    private fun armQualityLoadTimers\(\) \{.*?\n    \}\n",
    r"    /\*\*\n     \* Item D — the current variant's network handshake blew.*?\n    private fun onSlowHandshake\(\) \{.*?\n    \}\n",
    r"    /\*\*\n     \* Item D — a repeated-stall or slow-handshake signal.*?\n    private fun downgradeToLowestQuality\(\) \{.*?\n    \}\n",
    r"    /\*\*\n     \* Item D — high-bandwidth recovery.*?\n    private fun maybeUpgradeQuality\(\) \{.*?\n    \}\n"
]
for p in functions_to_remove:
    content = re.sub(p, "", content, flags=re.DOTALL)


with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Done PlayerActivity.kt")
