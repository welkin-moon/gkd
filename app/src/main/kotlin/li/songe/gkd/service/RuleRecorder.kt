package li.songe.gkd.service

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import li.songe.gkd.app

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
    val selectorCandidates: List<String>,
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
    private var lastSignature: String? = null
    private var lastEventTime = 0L

    fun start(service: A11yService) {
        _actionsFlow.value = emptyList()
        nextId = 0L
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

        _actionsFlow.update { actions ->
            actions + RecordedRuleAction(
                id = nextId++,
                appId = appId,
                viewId = viewId,
                vid = vid,
                className = className,
                text = text,
                desc = desc,
                selectorCandidates = selectorCandidates,
            )
        }
    }

    private fun buildSelectorCandidates(
        viewId: String?,
        vid: String?,
        className: String?,
        text: String?,
        desc: String?,
    ): List<String> {
        return listOfNotNull(
            vid?.let { "[vid=${quoteSelectorValue(it)}]" },
            viewId?.let { "[id=${quoteSelectorValue(it)}]" },
            text?.takeIf { it.length <= 120 }?.let { "[text=${quoteSelectorValue(it)}]" },
            desc?.takeIf { it.length <= 120 }?.let { "[desc=${quoteSelectorValue(it)}]" },
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
}
