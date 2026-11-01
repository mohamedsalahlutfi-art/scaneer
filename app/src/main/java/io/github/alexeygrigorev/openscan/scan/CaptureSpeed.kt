package io.github.alexeygrigorev.openscan.scan

/**
 * Scanning speed profile for the batch camera. Stored by stable [id] in
 * settings. BALANCED keeps the tuned V3 values; TURBO trades a little
 * resolution and steadiness for faster page flipping; QUALITY waits for a
 * steadier hold and stores larger frames.
 */
enum class CaptureSpeed(
    val id: String,
    val title: String,
    val description: String,
    /** Gap between edge-detection passes on the live preview. */
    val analysisIntervalMs: Long,
    /** Consecutive agreeing detections before an automatic snap. */
    val stableFrames: Int,
    /** Minimum quiet time between two automatic snaps. */
    val cooldownMs: Long,
    /** Longest side of the stored camera frame. */
    val saveMaxDim: Int,
) {
    TURBO(
        id = "turbo",
        title = "Turbo",
        description = "Fastest page flipping for long stacks. Slightly smaller images.",
        analysisIntervalMs = 110L,
        stableFrames = 1,
        cooldownMs = 700L,
        saveMaxDim = 2600,
    ),
    BALANCED(
        id = "balanced",
        title = "Balanced",
        description = "Recommended: fast and accurate edges.",
        analysisIntervalMs = 160L,
        stableFrames = 2,
        cooldownMs = 1_100L,
        saveMaxDim = 3200,
    ),
    QUALITY(
        id = "quality",
        title = "Maximum quality",
        description = "Waits for a steadier hold and keeps larger images. Best for small print.",
        analysisIntervalMs = 200L,
        stableFrames = 3,
        cooldownMs = 1_500L,
        saveMaxDim = 3600,
    );

    companion object {
        val DEFAULT = BALANCED

        /** Resolves a stored id, falling back to [DEFAULT] for null/unknown. */
        fun fromId(id: String?): CaptureSpeed = entries.firstOrNull { it.id == id } ?: DEFAULT

        /** Largest frame any profile stores; the save step decodes up to this. */
        val MAX_SAVE_DIM: Int = entries.maxOf { it.saveMaxDim }
    }
}
