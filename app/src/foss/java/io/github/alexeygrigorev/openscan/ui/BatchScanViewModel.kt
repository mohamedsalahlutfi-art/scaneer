package io.github.alexeygrigorev.openscan.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexeygrigorev.openscan.data.DocumentsRepository
import io.github.alexeygrigorev.openscan.data.SettingsRepository
import io.github.alexeygrigorev.openscan.scan.DocumentDuplicateDetector
import io.github.alexeygrigorev.openscan.scan.DocumentFingerprint
import io.github.alexeygrigorev.openscan.scan.CaptureQuality
import io.github.alexeygrigorev.openscan.scan.CaptureRefine
import io.github.alexeygrigorev.openscan.scan.CaptureSpeed
import io.github.alexeygrigorev.openscan.scan.Images
import io.github.alexeygrigorev.openscan.scan.QuadPlausibility
import io.github.alexeygrigorev.openscan.scan.ScanFilter
import io.github.alexeygrigorev.openscan.scan.ScanPipeline
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One page of an in-progress batch capture: the full camera frame on disk
 * (cache dir) plus the document quad detected on it, in frame coordinates
 * (TL, TR, BR, BL). [quad] is null when detection failed — the page still
 * ships as the raw frame and the user corrects it in the review editor.
 * [autoQuad] is the detector's original answer and never changes after
 * capture; [refinedQuad] is the post-capture refinement (item 8b, null when
 * refinement found nothing better); [quad] is the working copy the review
 * editor modifies. Together they become the training/eval triple stored on
 * the saved page (items 5+8). [needsCheck] marks refinement-flagged pages so
 * the review grid can highlight "check corners"; any manual edit clears it.
 * [userEdited] records a manual Done / full-frame choice so the saved
 * user-corners stay null when the user never touched the quad.
 * [version] bumps on every quad/rotation edit so preview thumbnails can
 * invalidate their cache.
 */
data class CapturedPage(
    val id: Long,
    val file: File,
    val frameWidth: Int,
    val frameHeight: Int,
    val quad: FloatArray?,
    val autoQuad: FloatArray? = quad,
    val refinedQuad: FloatArray? = null,
    val needsCheck: Boolean = false,
    val userEdited: Boolean = false,
    val rotation: Int = 0,
    val version: Int = 0,
    val fingerprint: DocumentFingerprint? = null,
    /** Per-page enhancement override ([ScanFilter.id]); null follows the batch filter. */
    val filter: String? = null,
) {
    override fun equals(other: Any?) =
        other is CapturedPage && other.id == id && other.version == version

    override fun hashCode() = (id * 31 + version).toInt()
}

/** Latest viewfinder detection, used for the overlay and the stability UI. */
/** A possible repeat; review it explicitly. No page is removed automatically. */
data class DuplicateWarning(val page: CapturedPage, val matchedPageNumber: Int)

/** Suspends automatic capture until an uncertain crop is reviewed. */
data class BoundaryConfirmation(val page: CapturedPage, val reason: String)

data class FrameDetection(
    val quad: FloatArray?,
    val frameWidth: Int,
    val frameHeight: Int,
    val stable: Boolean,
    /** Shadow / low-contrast / blur warning; auto-capture waits while set. */
    val issue: String? = null,
)

/**
 * State machine behind the foss batch capture (src/foss CaptureRoute):
 * the camera streams analysis frames in via [onAnalysisFrame], pages are
 * captured automatically once the detector holds a stable document quad
 * (manual shutter always available), and the user keeps flipping pages until
 * Stop. Stop moves to the review phase, where every page can be corrected —
 * drag the quad corners, re-detect, rotate, delete — before [save] warps
 * the survivors through their final quads into a document.
 */
class BatchScanViewModel(
    private val appendTo: Long?,
    private val repository: DocumentsRepository,
    private val settings: SettingsRepository,
    context: Context,
) : ViewModel() {

    enum class Phase { CAPTURE, REVIEW }

    val phase = MutableStateFlow(Phase.CAPTURE)
    val pages = MutableStateFlow<List<CapturedPage>>(emptyList())
    val pageCount = MutableStateFlow(0)
    val detection = MutableStateFlow<FrameDetection?>(null)
    val duplicateWarning = MutableStateFlow<DuplicateWarning?>(null)
    val boundaryConfirmation = MutableStateFlow<BoundaryConfirmation?>(null)

    /** "All pages" enhancement for this batch; null means the app default. */
    val batchFilter = MutableStateFlow<String?>(null)

    /** App-wide default enhancement, shown as the batch filter until one is picked. */
    val appFilterDefault: Flow<String> = settings.filterDefault

    /** Bumped once per captured page: the UI flashes and buzzes on change. */
    val snapTick = MutableStateFlow(0)

    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** (page index, total) while saving; null when idle. */
    val saveProgress = MutableStateFlow<Pair<Int, Int>?>(null)

    fun dismissError() {
        error.value = null
    }

    /** Set by the UI: fires the camera's ImageCapture use case. */
    var captureShutter: (() -> Unit)? = null

    /**
     * Set by the UI: runs autofocus metering, then invokes the given
     * shoot block (item 10). Unset (tests, previews) shoots immediately.
     */
    var focusAndShoot: ((shoot: () -> Unit) -> Unit)? = null

    /** The Settings auto-capture toggle (item 9) for the hint/status UI. */
    val autoCaptureEnabled: Flow<Boolean> = settings.autoCaptureEnabled

    // FilesDir (not cacheDir) allows resuming a long 100+ page session after
    // Android reclaims the process, or the user closes the camera by mistake.
    private val batchDir: File = File(context.filesDir, "smartscan-batch-${appendTo ?: "new"}").apply { mkdirs() }
    private val sessionFile = File(batchDir, "session.json")
    private var nextId = 1L

    // --- capture trigger state (detect thread + main) ---

    private val detectExecutor = Executors.newSingleThreadExecutor()
    private val detectContext: CoroutineContext = detectExecutor.asCoroutineDispatcher()

    private val analyzing = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    private var lastAnalysisAt = 0L
    private var lastQuad: FloatArray? = null
    private var lastQuadFrame: Pair<Int, Int>? = null
    private var stableCount = 0
    private var lostSinceLastCapture = true
    private var lastCaptureAt = 0L
    private var lastCapturedQuad: FloatArray? = null
    private var lastCapturedFrame: Pair<Int, Int>? = null

    private val thumbCache = object : LruCache<Long, ImageBitmap>(20) {}

    /**
     * Active speed profile. Read on the camera analyzer thread for every
     * frame, so it is a cached volatile copy of the DataStore value.
     */
    @Volatile
    private var speed: CaptureSpeed = CaptureSpeed.DEFAULT

    init {
        restoreSession()
        viewModelScope.launch {
            settings.captureSpeed.collect { speed = CaptureSpeed.fromId(it) }
        }
    }

    // Cheap gate the analyzer consults before decoding a frame at all.
    fun wantsFrame(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean =
        phase.value == Phase.CAPTURE && duplicateWarning.value == null && boundaryConfirmation.value == null && !capturing.get() &&
            nowElapsed - lastAnalysisAt >= speed.analysisIntervalMs

    /**
     * Upright analysis frame from the camera. Runs detection on the detect
     * thread; when the same quad has held long enough since the last capture,
     * fires the shutter again — the user just keeps flipping pages.
     */
    fun onAnalysisFrame(bitmap: Bitmap, nowElapsed: Long = SystemClock.elapsedRealtime()) {
        lastAnalysisAt = nowElapsed
        if (!analyzing.compareAndSet(false, true)) return
        viewModelScope.launch(detectContext) {
            try {
                val quad = runCatching { ScanPipeline.detectQuad(bitmap) }.getOrNull()
                val stable = quad != null && isStable(quad, lastQuad, bitmap.width, bitmap.height)
                lastQuad = quad
                lastQuadFrame = bitmap.width to bitmap.height
                if (quad == null) lostSinceLastCapture = true
                stableCount = if (stable) stableCount + 1 else 0
                val profile = speed
                val stableEnough = stableCount >= profile.stableFrames
                // Quality gate only once the corners hold (cheap: ~10 ms on a
                // 360x480 warp), so the moving-phone phase costs nothing.
                val issue = if (quad != null && stableEnough) CaptureQuality.assess(bitmap, quad) else null
                detection.value = FrameDetection(quad, bitmap.width, bitmap.height, stableEnough, issue?.message)
                val cooldownOver = nowElapsed - lastCaptureAt >= profile.cooldownMs
                val sceneChanged = lostSinceLastCapture ||
                    movedEnough(
                        quad, lastQuadFrame,
                        lastCapturedQuad, lastCapturedFrame,
                    )
                // Auto-capture (item 9) fires only while the Settings toggle
                // is on; otherwise the outline stays a hint and the shutter
                // is the only trigger. The toggle read is DataStore-cached.
                val autoOn = runCatching { settings.autoCaptureEnabled.first() }
                    .getOrDefault(false)
                if (autoOn && stableEnough && issue == null && cooldownOver && sceneChanged &&
                    pages.value.size < AUTO_CAPTURE_MAX_PAGES
                ) {
                    requestCapture()
                }
            } finally {
                bitmap.recycle()
                analyzing.set(false)
            }
        }
    }

    /** Manual shutter or auto trigger: one full-res still through the pipeline. */
    fun requestCapture() {
        if (phase.value != Phase.CAPTURE || busy.value || duplicateWarning.value != null || boundaryConfirmation.value != null) return
        if (!capturing.compareAndSet(false, true)) return
        if (pages.value.size >= HARD_MAX_PAGES) {
            capturing.set(false)
            error.value = "Page limit reached (${HARD_MAX_PAGES}). Finish the batch to continue."
            return
        }
        lastCaptureAt = SystemClock.elapsedRealtime()
        val shoot = { captureShutter?.invoke() ?: capturing.set(false) }
        // Focus lock first (item 10); without a UI hook shoot immediately.
        try {
            focusAndShoot?.invoke(shoot) ?: shoot()
        } catch (_: Throwable) {
            shoot()
        }
    }

    /** Full-res upright still from ImageCapture; refined on the detect thread. */
    fun onStillCaptured(stillFull: Bitmap) {
        viewModelScope.launch(detectContext) {
            var boundedFrame: Bitmap? = null
            try {
                // Bound the stored frame like every other page import.
                val still = downscaleMax(stillFull, speed.saveMaxDim)
                boundedFrame = still
                val live = scaleQuad(lastQuad, lastQuadFrame, still.width, still.height)
                // Post-capture refinement (item 8b): a slower full-res pass
                // with plausibility checks; falls back to the live quad.
                val refined = runCatching { CaptureRefine.refine(still, live) }.getOrNull()
                val quad = refined?.quad ?: live
                val file = File(batchDir, "page-${nextId}.jpg")
                Images.saveJpeg(still, file)
                val fingerprint = DocumentDuplicateDetector.fromCameraBitmap(still, quad)
                val candidate = CapturedPage(
                    id = nextId++,
                    file = file,
                    frameWidth = still.width,
                    frameHeight = still.height,
                    quad = quad,
                    autoQuad = live ?: quad,
                    refinedQuad = refined?.refined,
                    needsCheck = refined?.needsCheck ?: true,
                    fingerprint = fingerprint,
                )
                // Re-arm only after a genuine page change. The photo itself is
                // saved before showing a duplicate alert so it can be kept.
                lastCapturedQuad = quad
                lastCapturedFrame = still.width to still.height
                lostSinceLastCapture = false
                val prior = fingerprint?.let { fp ->
                    DocumentDuplicateDetector.findCandidate(fp, pages.value.mapIndexedNotNull { i, priorPage ->
                        priorPage.fingerprint?.let { i + 1 to it }
                    })
                }
                if (prior == null) {
                    if (candidate.quad == null || candidate.needsCheck) {
                        boundaryConfirmation.value = BoundaryConfirmation(
                            candidate,
                            if (candidate.quad == null) "No reliable page boundary was found." else "Please check the detected corners."
                        )
                    } else {
                        acceptPage(candidate)
                    }
                } else {
                    duplicateWarning.value = DuplicateWarning(candidate, prior.pageNumber)
                }
            } catch (t: Throwable) {
                error.value = t.message ?: "Could not save the page"
            } finally {
                boundedFrame?.takeIf { it !== stillFull && !it.isRecycled }?.recycle()
                if (!stillFull.isRecycled) stillFull.recycle()
                capturing.set(false)
            }
        }
    }

    /**
     * Accept the uncertain capture. [editedQuad] carries corners the user
     * dragged on the confirmation screen; [useFullFrame] keeps the whole
     * photo uncropped. Either way the page can still be edited in Review.
     */
    fun confirmBoundary(editedQuad: FloatArray? = null, useFullFrame: Boolean = false) {
        val pending = boundaryConfirmation.value ?: return
        boundaryConfirmation.value = null
        val page = when {
            useFullFrame -> pending.page.copy(quad = null, needsCheck = false, userEdited = true)
            editedQuad != null -> pending.page.copy(quad = editedQuad, needsCheck = false, userEdited = true)
            else -> pending.page
        }
        acceptPage(page)
    }

    /** Reject an uncertain capture without deleting previously accepted pages. */
    fun retakeBoundary() {
        val pending = boundaryConfirmation.value ?: return
        boundaryConfirmation.value = null
        pending.page.file.delete()
        lostSinceLastCapture = true
    }

    /** Confirm a suspected repeat. Never silently discard it. */
    fun keepDuplicate() {
        val warning = duplicateWarning.value ?: return
        duplicateWarning.value = null
        acceptPage(warning.page)
    }

    /** Explicitly ignore this capture; previous pages are left untouched. */
    fun skipDuplicate() {
        val warning = duplicateWarning.value ?: return
        duplicateWarning.value = null
        warning.page.file.delete()
    }

    private fun acceptPage(page: CapturedPage) {
        pages.value = pages.value + page
        pageCount.value = pages.value.size
        persistSession()
        snapTick.value += 1
    }

    private fun downscaleMax(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    fun onCaptureFailed(t: Throwable) {
        error.value = t.message ?: "Capture failed"
        capturing.set(false)
    }

    // --- phase + review edits ---

    fun stop() {
        if (pages.value.isNotEmpty()) phase.value = Phase.REVIEW
    }

    fun resumeCapture() {
        phase.value = Phase.CAPTURE
        lostSinceLastCapture = true
    }

    fun deletePage(id: Long) {
        val page = pages.value.firstOrNull { it.id == id } ?: return
        page.file.delete()
        pages.value = pages.value.filterNot { it.id == id }
        pageCount.value = pages.value.size
        thumbCache.remove(id)
        persistSession()
    }

    fun rotatePage(id: Long) = editPage(id) { it.copy(rotation = (it.rotation + 1) % 4) }

    /** Manual corner correction: the user takes responsibility (clears the flag). */
    fun setQuad(id: Long, quad: FloatArray) =
        editPage(id) { it.copy(quad = quad, needsCheck = false, userEdited = true) }

    fun clearQuad(id: Long) =
        editPage(id) { it.copy(quad = null, needsCheck = false, userEdited = true) }

    /** Per-page filter override; null makes the page follow the batch filter. */
    fun setPageFilter(id: Long, filterId: String?) {
        val page = pages.value.firstOrNull { it.id == id } ?: return
        if (page.filter != filterId) editPage(id) { it.copy(filter = filterId) }
    }

    /** "All pages" filter: applies to the whole batch and clears page overrides. */
    fun setBatchFilter(filterId: String) {
        batchFilter.value = filterId
        pages.value = pages.value.map { it.copy(filter = null, version = it.version + 1) }
        thumbCache.evictAll()
        persistSession()
    }

    /** Re-runs the detector on the stored frame (review editor's Auto button). */
    fun autoDetect(id: Long) {
        val page = pages.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val decoded = Images.decodeScaled(page.file, AUTODETECT_MAX_DIM)
                ?: return@launch
            val quad = runCatching { ScanPipeline.detectQuad(decoded) }.getOrNull()
                ?.let { scaleQuad(it, decoded.width to decoded.height, page.frameWidth, page.frameHeight) }
            decoded.recycle()
            if (quad != null) {
                // A re-detection is a new refined answer, not a user edit;
                // flag it when it is itself implausible.
                val flagged = !QuadPlausibility.check(quad, page.frameWidth, page.frameHeight).plausible
                editPage(id) { it.copy(quad = quad, refinedQuad = quad, needsCheck = flagged) }
            }
        }
    }

    private fun editPage(id: Long, edit: (CapturedPage) -> CapturedPage) {
        pages.value = pages.value.map {
            if (it.id == id) edit(it).let { p -> p.copy(version = p.version + 1) } else it
        }
        thumbCache.remove(id)
        persistSession()
    }

    // --- previews ---

    /** Warped (and rotated) page preview for the review grid, cached by page version. */
    suspend fun thumbnail(page: CapturedPage): ImageBitmap? {
        thumbCache.get(page.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            // The review shows the same enhancement the export will embed.
            val filter = ScanFilter.effective(
                page.filter,
                batchFilter.value,
                runCatching { settings.filterDefault.first() }.getOrNull(),
            )
            val rendered = renderPage(page, THUMB_MAX_DIM) ?: return@withContext null
            val enhanced = if (filter == io.github.alexeygrigorev.openscan.scan.ScanFilter.ORIGINAL) {
                rendered
            } else {
                io.github.alexeygrigorev.openscan.scan.ScanFilters.apply(rendered, filter)
                    ?.also { rendered.recycle() } ?: rendered
            }
            enhanced.asImageBitmap().also { thumbCache.put(page.id, it) }
        }
    }

    /** Full-frame bitmap for the review editor, downscaled to [maxDim]. */
    suspend fun editorFrame(page: CapturedPage, maxDim: Int = EDITOR_MAX_DIM): Bitmap? =
        withContext(Dispatchers.IO) { Images.decodeScaled(page.file, maxDim) }

    // --- save ---

    /** Warps every kept page through its final quad into the document. */
    fun save(onDone: (documentId: Long, added: Int, failed: Int) -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                // The target may have been deleted while capturing; fall back
                // to a fresh document rather than orphan the pages.
                val documentId = appendTo
                    ?.takeIf { repository.getDocument(it) != null }
                    ?: repository.createDocument()
                val isNewDocument = documentId != appendTo
                // New document: the batch filter becomes the document filter
                // (and the app default, like choosing it on the document).
                // Appending: apply it per page so older pages keep theirs.
                val batch = batchFilter.value
                if (batch != null && isNewDocument) {
                    repository.setDocumentFilter(documentId, batch)
                    runCatching { settings.setFilterDefault(batch) }
                }
                val list = pages.value
                var failed = 0
                // Read once: the original frames move into permanent storage
                // (keyed by page id) unless the user opted out of keeping them.
                val keepOriginals = runCatching { settings.keepOriginalsEnabled.first() }
                    .getOrDefault(true)
                list.forEachIndexed { index, page ->
                    saveProgress.value = index to list.size
                    try {
                        // Disk I/O and full-resolution OpenCV work must not run
                        // on the UI thread, especially with hundreds of pages.
                        withContext(Dispatchers.IO) {
                            val source = Images.decodeScaled(page.file, CaptureSpeed.MAX_SAVE_DIM)
                                ?: throw IllegalStateException("cannot decode ${page.file.name}")
                            var rendered: Bitmap? = null
                            try {
                                val completed = renderPageInto(source, page)
                                rendered = completed
                                val saved = repository.importPageBitmap(
                                    documentId,
                                    completed,
                                    detected = true,
                                    original = if (keepOriginals) page.file else null,
                                    autoCorners = page.autoQuad,
                                    refinedCorners = page.refinedQuad,
                                    userCorners = if (page.userEdited) page.quad else null,
                                    imageWidth = page.frameWidth,
                                    imageHeight = page.frameHeight,
                                )
                                val override = page.filter ?: batch?.takeIf { !isNewDocument }
                                if (override != null) repository.setPageFilter(saved.id, override)
                            } finally {
                                rendered?.takeIf { it !== source }?.recycle()
                                source.recycle()
                            }
                        }
                    } catch (_: Throwable) {
                        failed++
                    }
                }
                busy.value = false
                saveProgress.value = null
                batchDir.deleteRecursively()
                onDone(documentId, list.size - failed, failed)
            } catch (t: Throwable) {
                busy.value = false
                saveProgress.value = null
                error.value = t.message ?: "Could not create the document"
            }
        }
    }

    fun discard() {
        duplicateWarning.value?.page?.file?.delete()
        duplicateWarning.value = null
        boundaryConfirmation.value?.page?.file?.delete()
        boundaryConfirmation.value = null
        batchDir.deleteRecursively()
        pages.value = emptyList()
        pageCount.value = 0
    }

    /** Warped/rotated page at [maxDim]; the raw frame when no quad is set. */
    private suspend fun renderPage(page: CapturedPage, maxDim: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val source = Images.decodeScaled(page.file, maxDim) ?: return@withContext null
            renderPageInto(source, page)
        }

    private fun renderPageInto(source: Bitmap, page: CapturedPage): Bitmap {
        val warped = page.quad
            ?.let { scaleQuad(it, page.frameWidth to page.frameHeight, source.width, source.height) }
            ?.let { runCatching { ScanPipeline.crop(source, it) }.getOrNull() }
            ?: source
        if (page.rotation == 0) return warped
        val matrix = Matrix().apply { postRotate(90f * page.rotation) }
        val rotated = Bitmap.createBitmap(warped, 0, 0, warped.width, warped.height, matrix, true)
        if (rotated !== warped && warped !== source) warped.recycle()
        return rotated
    }

    /** Compact file manifest; each page's image is already safely on disk. */
    @Synchronized
    private fun persistSession() {
        runCatching {
            val data = JSONObject().put("nextId", nextId)
            val entries = JSONArray()
            pages.value.forEach { page ->
                fun floats(a: FloatArray?): JSONArray? = a?.let { array ->
                    JSONArray().also { output -> array.forEach { output.put(it.toDouble()) } }
                }
                val item = JSONObject()
                    .put("id", page.id).put("file", page.file.name)
                    .put("width", page.frameWidth).put("height", page.frameHeight)
                    .put("rotation", page.rotation).put("edited", page.userEdited)
                    .put("check", page.needsCheck).put("quad", floats(page.quad))
                    .put("auto", floats(page.autoQuad)).put("refined", floats(page.refinedQuad))
                page.filter?.let { item.put("filter", it) }
                page.fingerprint?.let { fp ->
                    item.put("fpH", fp.horizontal.toString())
                        .put("fpV", fp.vertical.toString())
                        .put("ink", fp.inkPermille)
                    item.put("grid", JSONArray().also { grid -> fp.inkGrid.forEach { grid.put(it) } })
                }
                entries.put(item)
            }
            data.put("pages", entries)
            batchFilter.value?.let { data.put("batchFilter", it) }
            val tmp = File(batchDir, "session.json.tmp")
            tmp.writeText(data.toString())
            check(tmp.renameTo(sessionFile)) { "Unable to save scan session" }
        }.onFailure { t -> error.value = "Could not safeguard session: ${t.message}" }
    }

    private fun restoreSession() {
        if (!sessionFile.isFile) return
        runCatching {
            val root = JSONObject(sessionFile.readText())
            val entries = root.getJSONArray("pages")
            fun floats(item: JSONObject, name: String): FloatArray? {
                val array = item.optJSONArray(name) ?: return null
                if (array.length() != 8) return null
                return FloatArray(8) { i -> array.optDouble(i, 0.0).toFloat() }
            }
            val restored = buildList {
                for (i in 0 until entries.length()) {
                    val item = entries.getJSONObject(i)
                    val file = File(batchDir, item.getString("file"))
                    if (!file.isFile || !file.canonicalPath.startsWith(batchDir.canonicalPath + File.separator)) continue
                    val grid = item.optJSONArray("grid")
                    val fp = if (grid?.length() == 64) DocumentFingerprint(
                        item.getString("fpH").toLong(), item.getString("fpV").toLong(),
                        IntArray(64) { grid.getInt(it) }, item.getInt("ink"),
                    ) else null
                    add(CapturedPage(
                        id = item.getLong("id"), file = file,
                        frameWidth = item.getInt("width"), frameHeight = item.getInt("height"),
                        quad = floats(item, "quad"), autoQuad = floats(item, "auto"),
                        refinedQuad = floats(item, "refined"),
                        needsCheck = item.optBoolean("check", false),
                        userEdited = item.optBoolean("edited", false),
                        rotation = item.optInt("rotation", 0), fingerprint = fp,
                        filter = item.optString("filter", "").takeIf { it.isNotEmpty() },
                    ))
                }
            }
            pages.value = restored
            pageCount.value = restored.size
            batchFilter.value = root.optString("batchFilter", "").takeIf { it.isNotEmpty() }
            nextId = maxOf(root.optLong("nextId", 1L), (restored.maxOfOrNull { it.id } ?: 0L) + 1)
            // The old sheet must be removed/replaced before another auto-snap.
            lostSinceLastCapture = true
        }.onFailure { error.value = "Saved session could not be restored: ${it.message}" }
    }

    override fun onCleared() {
        // Preserve the session: Android may destroy the view model while the
        // user switches apps. An explicit Discard or successful Save cleans it.
        duplicateWarning.value?.page?.file?.delete()
        detectExecutor.shutdown()
    }

    // --- quad geometry helpers ---

    /** Same quad (cornerwise) across consecutive frames, within [STABLE_DELTA]. */
    private fun isStable(q: FloatArray?, last: FloatArray?, w: Int, h: Int): Boolean {
        if (q == null || last == null) return false
        val norm = hypot(w.toDouble(), h.toDouble())
        for (i in 0 until 4) {
            val dx = (q[i * 2] - last[i * 2]).toDouble()
            val dy = (q[i * 2 + 1] - last[i * 2 + 1]).toDouble()
            if (hypot(dx, dy) / norm > STABLE_DELTA) return false
        }
        return areaFrac(q, w, h) >= MIN_AREA_FRAC
    }

    /**
     * A new page placement: the quad visibly moved, or detection dropped out
     * in between (the hand turning the page). Small jitter alone does not
     * re-arm, so a page left under the camera is not captured twice.
     */
    private fun movedEnough(
        q: FloatArray?,
        qFrame: Pair<Int, Int>?,
        prev: FloatArray?,
        prevFrame: Pair<Int, Int>?,
    ): Boolean {
        if (q == null || qFrame == null || prev == null || prevFrame == null) return true
        var maxDelta = 0.0
        for (i in 0 until 4) {
            val dx = q[i * 2].toDouble() / qFrame.first - prev[i * 2].toDouble() / prevFrame.first
            val dy = q[i * 2 + 1].toDouble() / qFrame.second - prev[i * 2 + 1].toDouble() / prevFrame.second
            maxDelta = max(maxDelta, hypot(dx, dy))
        }
        // maxDelta is ALREADY normalized by image width/height. Dividing by
        // a pixel-length diagonal made this threshold effectively impossible.
        return maxDelta > RESNAP_DELTA
    }

    private fun areaFrac(q: FloatArray, w: Int, h: Int): Float {
        var a = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            a += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        return abs(a) / 2f / (w * h)
    }

    /** Rescales a quad from one frame size to another (null-safe). */
    fun scaleQuad(
        quad: FloatArray?,
        from: Pair<Int, Int>?,
        toW: Int,
        toH: Int,
    ): FloatArray? {
        if (quad == null || from == null || from.first == 0 || from.second == 0) return null
        val sx = toW.toFloat() / from.first
        val sy = toH.toFloat() / from.second
        return FloatArray(8) { i -> if (i % 2 == 0) quad[i] * sx else quad[i] * sy }
    }

    companion object {

        /**
         * Detection cadence on the analysis stream (item 8a): 160 ms keeps
         * the overlay live and reaches a lock sooner on clean sheets without
         * starving the detect thread (a detect pass costs ~100-200 ms).
         */
        const val ANALYSIS_INTERVAL_MS = 160L

        /** Consecutive agreeing frames before an auto snap. */
        const val STABLE_FRAMES = 2

        /**
         * Max corner drift between frames that still counts as "held"
         * (item 8a): 4.5% of the frame diagonal tolerates normal hand shake
         * on a flat sheet; tighter values only delayed the lock.
         */
        const val STABLE_DELTA = 0.045

        /** Document must fill at least this frame fraction to auto-snap. */
        const val MIN_AREA_FRAC = 0.08f

        /** Min quiet period between auto snaps (item 8a: 1.1 s). */
        const val CAPTURE_COOLDOWN_MS = 1_100L

        /** Corner move (frame fraction) that re-arms the trigger without a dropout. */
        const val RESNAP_DELTA = 0.065

        /** Auto capture stops here; the manual shutter keeps working a while longer. */
        const val AUTO_CAPTURE_MAX_PAGES = 600

        /** Camera sessions support hundreds of pages (subject to free storage). */
        const val HARD_MAX_PAGES = 600

        const val THUMB_MAX_DIM = 440
        const val EDITOR_MAX_DIM = 2048
        const val AUTODETECT_MAX_DIM = 1600
        /** Stored-frame bound (item 10): full enough for ~300 DPI A4 warps. */
        const val SAVE_MAX_DIM = 3200
    }
}
