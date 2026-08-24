package li.songe.gkd.service

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import li.songe.gkd.app
import li.songe.gkd.data.AttrInfo
import li.songe.gkd.data.NodeInfo
import li.songe.gkd.data.info2nodeList
import li.songe.gkd.util.ScreenUtils
import java.io.File

/**
 * A lightweight frame kept only for the current recording session. Unlike the
 * normal Snapshot feature it is not inserted into the snapshot database.
 */
data class RecordedRuleFrame(
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<NodeInfo>,
    val screenshotPath: String?,
    val suggestedNodeId: Int?,
)

/**
 * Records user clicks from the accessibility service and converts the clicked
 * node attributes into selector candidates that can be reviewed on-device.
 */
data class RecordedRuleAction(
    val id: Long,
    val appId: String,
    val viewId: String?,
    val vid: String?,
    val className: String?,
    val text: String?,
    val desc: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val selectorCandidates: List<String>,
    val frame: RecordedRuleFrame? = null,
) {
    val defaultSelector: String
        get() = selectorCandidates.firstOrNull().orEmpty()

    val hasStableSelector: Boolean
        get() = vid != null || viewId != null || text != null || desc != null
}

object RuleRecorder {
    private const val MAX_ACTIONS = 100
    private const val IGNORE_AFTER_START_MS = 700L
    private const val DUPLICATE_WINDOW_MS = 250L

    private val _isRecordingFlow = MutableStateFlow(false)
    val isRecordingFlow: StateFlow<Boolean> = _isRecordingFlow.asStateFlow()

    private val _actionsFlow = MutableStateFlow<List<RecordedRuleAction>>(emptyList())
    val actionsFlow: StateFlow<List<RecordedRuleAction>> = _actionsFlow.asStateFlow()

    private var startedAt = 0L
    private var nextId = 0L
    private var sessionId = 0L
    private var lastSignature: String? = null
    private var lastEventTime = 0L

    private val cacheDir: File
        get() = File(app.cacheDir, "rule-recorder")

    fun start(service: A11yService) {
        clearCache()
        _actionsFlow.value = emptyList()
        nextId = 0L
        sessionId++
        lastSignature = null
        lastEventTime = 0L
        startedAt = SystemClock.uptimeMillis()
        _isRecordingFlow.value = true
        service.setRuleRecorderEventsEnabled(true)
    }

    fun stop(service: A11yService?): Int {
        _isRecordingFlow.value = false
        service?.setRuleRecorderEventsEnabled(false)
        return _actionsFlow.value.size
    }

    fun clear() {
        if (!_isRecordingFlow.value) {
            _actionsFlow.value = emptyList()
            clearCache()
        }
    }

    fun onA11yDisconnected() {
        _isRecordingFlow.value = false
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!_isRecordingFlow.value || event?.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return
        }
        if (event.eventTime - startedAt < IGNORE_AFTER_START_MS) return
        if (_actionsFlow.value.size >= MAX_ACTIONS) return

        val appId = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (appId == app.packageName || appId == "com.android.systemui") return

        val source = event.source ?: return
        val viewId = source.viewIdResourceName?.takeIf { it.isNotBlank() }
        val idPrefix = "$appId:id/"
        val vid = viewId?.takeIf { it.startsWith(idPrefix) }?.substring(idPrefix.length)
        val className = source.className?.toString()?.takeIf { it.isNotBlank() }
        val text = source.text?.toString()?.takeIf { it.isNotBlank() }
        val desc = source.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val bounds = Rect().also(source::getBoundsInScreen)

        val selectorCandidates = buildSelectorCandidates(
            viewId = viewId,
            vid = vid,
            className = className,
            text = text,
            desc = desc,
        )
        if (selectorCandidates.isEmpty()) return

        val signature = listOf(appId, viewId, className, text, desc).joinToString("\u0000")
        if (signature == lastSignature && event.eventTime - lastEventTime < DUPLICATE_WINDOW_MS) {
            return
        }
        lastSignature = signature
        lastEventTime = event.eventTime

        val actionId = nextId++
        val action = RecordedRuleAction(
            id = actionId,
            appId = appId,
            viewId = viewId,
            vid = vid,
            className = className,
            text = text,
            desc = desc,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            selectorCandidates = selectorCandidates,
        )
        _actionsFlow.update { actions -> actions + action }

        val service = A11yService.instance ?: return
        val captureSession = sessionId
        service.scope.launch(Dispatchers.Default) {
            captureFrame(service, action, captureSession)?.let { frame ->
                if (captureSession != sessionId) return@let
                _actionsFlow.update { actions ->
                    actions.map { current ->
                        if (current.id == actionId) current.copy(frame = frame) else current
                    }
                }
            }
        }
    }

    fun selectorCandidatesFor(action: RecordedRuleAction, nodeId: Int?): List<String> {
        val node = nodeId?.let { id -> action.frame?.nodes?.find { it.id == id } }
        return if (node != null) {
            buildSelectorCandidates(node.attr)
        } else {
            action.selectorCandidates
        }
    }

    private suspend fun captureFrame(
        service: A11yService,
        action: RecordedRuleAction,
        captureSession: Long,
    ): RecordedRuleFrame? {
        return runCatching {
            val root = service.ruleEngine.safeActiveWindow ?: return@runCatching null
            val nodes = info2nodeList(root)
            val screenshotPath = runCatching {
                service.screenshot()?.let { bitmap ->
                    saveRecorderScreenshot(bitmap, captureSession, action.id)
                }
            }.getOrNull()
            RecordedRuleFrame(
                screenWidth = ScreenUtils.getScreenWidth(),
                screenHeight = ScreenUtils.getScreenHeight(),
                nodes = nodes,
                screenshotPath = screenshotPath,
                suggestedNodeId = findSuggestedNodeId(nodes, action),
            )
        }.getOrNull()
    }

    private suspend fun saveRecorderScreenshot(
        bitmap: Bitmap,
        captureSession: Long,
        actionId: Long,
    ): String? = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val file = File(cacheDir, "$captureSession-$actionId.png")
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        } ?: return@withContext null
        val ok = file.outputStream().use { stream ->
            source.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        if (source !== bitmap) source.recycle()
        if (ok) file.absolutePath else null
    }

    private fun findSuggestedNodeId(
        nodes: List<NodeInfo>,
        action: RecordedRuleAction,
    ): Int? {
        val centerX = (action.left + action.right) / 2
        val centerY = (action.top + action.bottom) / 2
        return nodes.maxByOrNull { node ->
            val attr = node.attr
            var score = 0
            if (action.viewId != null && attr.id == action.viewId) score += 12
            if (action.text != null && attr.text == action.text) score += 8
            if (action.desc != null && attr.desc == action.desc) score += 8
            if (action.className != null && attr.name == action.className) score += 3
            if (
                centerX in attr.left..attr.right &&
                centerY in attr.top..attr.bottom
            ) score += 4
            if (
                attr.left == action.left && attr.top == action.top &&
                attr.right == action.right && attr.bottom == action.bottom
            ) score += 6
            score * 1000 + attr.depth
        }?.takeIf { node ->
            val attr = node.attr
            (action.viewId != null && attr.id == action.viewId) ||
                (action.text != null && attr.text == action.text) ||
                (action.desc != null && attr.desc == action.desc) ||
                (centerX in attr.left..attr.right && centerY in attr.top..attr.bottom)
        }?.id
    }

    private fun buildSelectorCandidates(attr: AttrInfo): List<String> =
        buildSelectorCandidates(
            viewId = attr.id,
            vid = attr.vid,
            className = attr.name,
            text = attr.text,
            desc = attr.desc,
        )

    private fun buildSelectorCandidates(
        viewId: String?,
        vid: String?,
        className: String?,
        text: String?,
        desc: String?,
    ): List<String> {
        val shortText = text?.takeIf { it.length <= 120 }
        val shortDesc = desc?.takeIf { it.length <= 120 }
        return listOfNotNull(
            vid?.let { "[vid=${quoteSelectorValue(it)}]" },
            viewId?.let { "[id=${quoteSelectorValue(it)}]" },
            if (vid != null && shortText != null) {
                "[vid=${quoteSelectorValue(vid)}][text=${quoteSelectorValue(shortText)}]"
            } else null,
            if (vid != null && shortDesc != null) {
                "[vid=${quoteSelectorValue(vid)}][desc=${quoteSelectorValue(shortDesc)}]"
            } else null,
            shortText?.let { "[text=${quoteSelectorValue(it)}]" },
            shortDesc?.let { "[desc=${quoteSelectorValue(it)}]" },
            if (className != null && shortText != null) {
                "[name=${quoteSelectorValue(className)}][text=${quoteSelectorValue(shortText)}]"
            } else null,
            if (className != null && shortDesc != null) {
                "[name=${quoteSelectorValue(className)}][desc=${quoteSelectorValue(shortDesc)}]"
            } else null,
            className?.let { "[name=${quoteSelectorValue(it)}]" },
        ).distinct()
    }

    private fun quoteSelectorValue(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun clearCache() {
        runCatching { cacheDir.deleteRecursively() }
    }
}
