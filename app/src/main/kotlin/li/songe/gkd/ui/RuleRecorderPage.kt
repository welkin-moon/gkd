package li.songe.gkd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import li.songe.gkd.data.NodeInfo
import li.songe.gkd.data.SubscriptionInputParser
import li.songe.gkd.data.edit
import li.songe.gkd.service.RecordedRuleAction
import li.songe.gkd.service.RuleRecorder
import li.songe.gkd.ui.component.EmptyText
import li.songe.gkd.ui.component.PerfIcon
import li.songe.gkd.ui.component.PerfIconButton
import li.songe.gkd.ui.component.PerfTopAppBar
import li.songe.gkd.ui.share.LocalMainViewModel
import li.songe.gkd.ui.style.scaffoldPadding
import li.songe.gkd.util.LOCAL_SUBS_ID
import li.songe.gkd.util.SubscriptionStore
import li.songe.gkd.util.toast
import kotlin.math.max

@Serializable
data object RuleRecorderRoute : NavKey

@Composable
fun RuleRecorderPage() {
    val mainVm = LocalMainViewModel.current
    val actions by RuleRecorder.actionsFlow.collectAsStateWithLifecycle()
    val enabled = remember { mutableStateMapOf<Long, Boolean>() }
    val selectedNodeIds = remember { mutableStateMapOf<Long, Int>() }
    val selectorIndexes = remember { mutableStateMapOf<Long, Int>() }
    var selectedActionId by remember { mutableStateOf<Long?>(null) }
    var groupName by remember {
        mutableStateOf("录制规则-${System.currentTimeMillis().toString().takeLast(6)}")
    }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(actions) {
        actions.forEach { action ->
            enabled.putIfAbsent(action.id, action.hasStableSelector)
            selectorIndexes.putIfAbsent(action.id, 0)
            action.frame?.suggestedNodeId?.let { nodeId ->
                selectedNodeIds.putIfAbsent(action.id, nodeId)
            }
        }
        val actionIds = actions.mapTo(mutableSetOf()) { it.id }
        enabled.keys.retainAll(actionIds)
        selectedNodeIds.keys.retainAll(actionIds)
        selectorIndexes.keys.retainAll(actionIds)
        if (selectedActionId !in actionIds) {
            selectedActionId = actions.firstOrNull()?.id
        }
    }

    val currentAction = actions.find { it.id == selectedActionId } ?: actions.firstOrNull()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    PerfIconButton(
                        imageVector = PerfIcon.ArrowBack,
                        onClick = { mainVm.popPage() },
                    )
                },
                title = { Text("录制规则编辑") },
                actions = {
                    TextButton(
                        onClick = {
                            RuleRecorder.clear()
                            mainVm.popPage()
                        },
                        enabled = !saving,
                    ) {
                        Text("丢弃")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val selectedCount = actions.count { enabled[it.id] == true }
                Text(
                    text = if (selectedCount > 1) {
                        "$selectedCount 步 · 按录制顺序执行"
                    } else {
                        "$selectedCount 步"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    enabled = !saving && selectedCount > 0 && groupName.isNotBlank(),
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                saveRecordedRules(
                                    actions = actions,
                                    enabled = enabled,
                                    selectedNodeIds = selectedNodeIds,
                                    selectorIndexes = selectorIndexes,
                                    groupName = groupName.trim(),
                                )
                                RuleRecorder.clear()
                                toast("已按顺序加入本地订阅")
                                mainVm.popPage()
                            } catch (e: Exception) {
                                toast("保存失败：${e.message}", forced = true)
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) {
                    Text(if (saving) "保存中" else "加入本地订阅")
                }
            }
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.scaffoldPadding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (actions.isEmpty()) {
                item {
                    Spacer(Modifier.height(48.dp))
                    EmptyText(text = "没有录制到可处理的点击")
                }
            } else {
                item {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "操作顺序",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "关闭某一步后，前后步骤会自动重新连成顺序链。点击步骤可在下方截图中重新选元素。",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RuleSequenceStrip(
                            actions = actions,
                            enabled = enabled,
                            selectedActionId = currentAction?.id,
                            onSelect = { selectedActionId = it },
                            onEnabledChange = { id, value -> enabled[id] = value },
                        )
                    }
                }

                currentAction?.let { action ->
                    item(key = "picker-${action.id}") {
                        val selectedNodeId = selectedNodeIds[action.id]
                        RuleRecorderElementPicker(
                            action = action,
                            selectedNodeId = selectedNodeId,
                            selectorIndex = selectorIndexes[action.id] ?: 0,
                            onNodeSelected = { nodeId ->
                                selectedNodeIds[action.id] = nodeId
                                selectorIndexes[action.id] = 0
                            },
                            onSelectorSelected = { index ->
                                selectorIndexes[action.id] = index
                            },
                        )
                    }
                }

                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HorizontalDivider()
                        OutlinedTextField(
                            value = groupName,
                            onValueChange = { groupName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("规则组名称") },
                        )
                        Text(
                            text = "保存后仍是标准 GKD 本地订阅规则；多步操作会用 key / preKeys 编译成原生顺序关系，但这里不显示 JSON。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun RuleSequenceStrip(
    actions: List<RecordedRuleAction>,
    enabled: Map<Long, Boolean>,
    selectedActionId: Long?,
    onSelect: (Long) -> Unit,
    onEnabledChange: (Long, Boolean) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(actions, key = { it.id }) { action ->
            val index = actions.indexOf(action) + 1
            val active = enabled[action.id] == true
            val selected = action.id == selectedActionId
            Card(
                modifier = Modifier
                    .size(width = 136.dp, height = 92.dp)
                    .clickable { onSelect(action.id) }
                    .then(
                        if (selected) {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(12.dp),
                            )
                        } else Modifier
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(
                        checked = active,
                        onCheckedChange = { onEnabledChange(action.id, it) },
                        modifier = Modifier.size(32.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "步骤 $index",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = action.text ?: action.desc ?: action.vid ?: "点击元素",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (active) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRecorderElementPicker(
    action: RecordedRuleAction,
    selectedNodeId: Int?,
    selectorIndex: Int,
    onNodeSelected: (Int) -> Unit,
    onSelectorSelected: (Int) -> Unit,
) {
    val frame = action.frame
    val selectedNode = frame?.nodes?.find { it.id == selectedNodeId }
    val candidates = RuleRecorder.selectorCandidatesFor(action, selectedNodeId)
    val safeSelectorIndex = selectorIndex.coerceIn(0, max(0, candidates.lastIndex))

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "选择元素",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = action.appId,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (frame == null) "正在生成界面快照…" else "点截图重新选择",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (frame?.screenshotPath != null) {
            val nodeForHighlight = selectedNode ?: frame.suggestedNodeId?.let { id ->
                frame.nodes.find { it.id == id }
            }
            ScreenshotElementPicker(
                action = action,
                nodes = frame.nodes,
                screenshotPath = frame.screenshotPath,
                screenWidth = frame.screenWidth,
                screenHeight = frame.screenHeight,
                selectedNode = nodeForHighlight,
                onNodeSelected = onNodeSelected,
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (frame == null) {
                            "正在捕获节点树与截图"
                        } else {
                            "当前界面无法截图，可继续通过节点属性选择"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (frame != null) {
            ElementHierarchyControls(
                nodes = frame.nodes,
                selectedNode = selectedNode,
                fallbackNodeId = frame.suggestedNodeId,
                onNodeSelected = onNodeSelected,
            )
        }

        val node = selectedNode
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = node?.attr?.text ?: action.text ?: node?.attr?.desc ?: action.desc ?: "点击元素",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val attr = node?.attr
                listOfNotNull(
                    (attr?.vid ?: action.vid)?.let { "资源 ID · $it" },
                    (attr?.name ?: action.className)?.let { "控件 · ${it.substringAfterLast('.')}" },
                    attr?.desc?.takeIf { it != attr.text }?.let { "描述 · $it" },
                ).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Text(
            text = "匹配方式",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (candidates.isEmpty()) {
            Text(
                text = "这个节点没有可用的稳定属性，请在截图中选择其他元素。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            candidates.forEachIndexed { index, selector ->
                SelectorChoice(
                    selector = selector,
                    checked = index == safeSelectorIndex,
                    onClick = { onSelectorSelected(index) },
                )
            }
            Text(
                text = "越靠上的方案通常越稳定；组合条件更严格，适合页面上有多个相似元素时使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScreenshotElementPicker(
    action: RecordedRuleAction,
    nodes: List<NodeInfo>,
    screenshotPath: String,
    screenWidth: Int,
    screenHeight: Int,
    selectedNode: NodeInfo?,
    onNodeSelected: (Int) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val overlay = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val ratio = screenWidth.toFloat() / screenHeight.toFloat().coerceAtLeast(1f)
    val painter = rememberAsyncImagePainter(model = screenshotPath)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .pointerInput(nodes, screenWidth, screenHeight) {
                detectTapGestures { offset ->
                    if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                    val x = offset.x / size.width * screenWidth
                    val y = offset.y / size.height * screenHeight
                    findBestNodeAt(nodes, x, y)?.let { node ->
                        onNodeSelected(node.id)
                    }
                }
            },
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val node = selectedNode
            if (node != null && screenWidth > 0 && screenHeight > 0) {
                val attr = node.attr
                val left = attr.left.toFloat() / screenWidth * size.width
                val top = attr.top.toFloat() / screenHeight * size.height
                val right = attr.right.toFloat() / screenWidth * size.width
                val bottom = attr.bottom.toFloat() / screenHeight * size.height
                val width = (right - left).coerceAtLeast(1f)
                val height = (bottom - top).coerceAtLeast(1f)
                drawRect(
                    color = overlay,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                )
                drawRect(
                    color = primary,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(width = 3.dp.toPx()),
                )
            } else {
                val left = action.left.toFloat() / screenWidth * size.width
                val top = action.top.toFloat() / screenHeight * size.height
                val right = action.right.toFloat() / screenWidth * size.width
                val bottom = action.bottom.toFloat() / screenHeight * size.height
                drawRect(
                    color = primary,
                    topLeft = Offset(left, top),
                    size = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun ElementHierarchyControls(
    nodes: List<NodeInfo>,
    selectedNode: NodeInfo?,
    fallbackNodeId: Int?,
    onNodeSelected: (Int) -> Unit,
) {
    val current = selectedNode ?: fallbackNodeId?.let { id -> nodes.find { it.id == id } }
    val parent = current?.pid?.takeIf { it >= 0 }?.let { pid -> nodes.find { it.id == pid } }
    val children = current?.let { node -> nodes.filter { it.pid == node.id } }.orEmpty()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { parent?.let { onNodeSelected(it.id) } },
            enabled = parent != null,
            modifier = Modifier.weight(1f),
        ) {
            Text("选父元素")
        }
        OutlinedButton(
            onClick = {
                children.minByOrNull { child ->
                    val a = child.attr
                    ((a.right - a.left).coerceAtLeast(1).toLong() *
                        (a.bottom - a.top).coerceAtLeast(1).toLong())
                }?.let { onNodeSelected(it.id) }
            },
            enabled = children.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) {
            Text("选子元素")
        }
    }
}

@Composable
private fun SelectorChoice(
    selector: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val display = selectorToFriendlyText(selector)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .border(
                        width = if (checked) 6.dp else 2.dp,
                        color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(9.dp),
                    ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = display.first,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                )
                display.second?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun selectorToFriendlyText(selector: String): Pair<String, String?> {
    val labels = buildList {
        if ("[vid=" in selector) add("资源 ID")
        if ("[id=" in selector && "[vid=" !in selector) add("完整资源 ID")
        if ("[text=" in selector) add("文字")
        if ("[desc=" in selector) add("无障碍描述")
        if ("[name=" in selector) add("控件类型")
    }
    val title = when {
        labels.size >= 2 -> labels.joinToString(" + ")
        labels.size == 1 -> labels.first()
        else -> "自定义属性"
    }
    val detail = selector
        .replace(Regex("\\[(vid|id|text|desc|name)=\""), "")
        .replace("\"]", " · ")
        .trim(' ', '·')
        .takeIf { it.isNotBlank() }
    return title to detail
}

private fun findBestNodeAt(nodes: List<NodeInfo>, x: Float, y: Float): NodeInfo? {
    return nodes
        .asSequence()
        .filter { node ->
            val a = node.attr
            x >= a.left && x <= a.right && y >= a.top && y <= a.bottom
        }
        .minWithOrNull(
            compareBy<NodeInfo> { node ->
                val a = node.attr
                (a.right - a.left).coerceAtLeast(1).toLong() *
                    (a.bottom - a.top).coerceAtLeast(1).toLong()
            }.thenByDescending { it.attr.depth }
        )
}

private suspend fun saveRecordedRules(
    actions: List<RecordedRuleAction>,
    enabled: Map<Long, Boolean>,
    selectedNodeIds: Map<Long, Int>,
    selectorIndexes: Map<Long, Int>,
    groupName: String,
) {
    val selected = actions.filter { enabled[it.id] == true }
    require(selected.isNotEmpty()) { "至少选择一个步骤" }

    selected.groupBy { it.appId }.forEach { (appId, appActions) ->
        val rules = appActions.mapIndexed { index, action ->
            val candidates = RuleRecorder.selectorCandidatesFor(
                action = action,
                nodeId = selectedNodeIds[action.id],
            )
            val selectorIndex = (selectorIndexes[action.id] ?: 0)
                .coerceIn(0, max(0, candidates.lastIndex))
            val selector = candidates.getOrNull(selectorIndex) ?: action.defaultSelector
            require(selector.isNotBlank()) { "步骤 ${index + 1} 没有可用选择器" }
            buildString {
                append("{ key: ${index + 1}")
                if (index > 0) {
                    append(", preKeys: [${index}]")
                }
                append(", matches: [${quoteJson5(selector)}] }")
            }
        }.joinToString(",\n")

        val scopedName = if (selected.map { it.appId }.distinct().size > 1) {
            "$groupName-${appId.substringAfterLast('.')}"
        } else {
            groupName
        }
        val input = SubscriptionInputParser.parse(
            """
            {
              name: ${quoteJson5(scopedName)},
              rules: [
                $rules
              ],
            }
            """.trimIndent(),
        )
        val groups = input.parseAppGroups(appId)
        SubscriptionStore.update(LOCAL_SUBS_ID) { subscription ->
            subscription.edit {
                appendAppGroups(
                    targetApp = subscription.getApp(appId),
                    groups = groups,
                )
            }
        }
    }
}

private fun quoteJson5(value: String): String = buildString {
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
