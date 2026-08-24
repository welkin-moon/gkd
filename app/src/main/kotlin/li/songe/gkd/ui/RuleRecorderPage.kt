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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import kotlin.math.roundToInt

@Serializable
data object RuleRecorderRoute : NavKey

@Composable
fun RuleRecorderPage() {
    val mainVm = LocalMainViewModel.current
    val actions by RuleRecorder.actionsFlow.collectAsStateWithLifecycle()
    val enabled = remember { mutableStateMapOf<Long, Boolean>() }
    val anchorNodeIds = remember { mutableStateMapOf<Long, Int>() }
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
            val frame = action.frame
            val suggestedNode = frame?.suggestedNodeId?.let { nodeId ->
                frame.nodes.find { it.id == nodeId }
            }
            if (frame != null && suggestedNode != null) {
                val anchor = findFineAnchor(frame.nodes, suggestedNode)
                anchorNodeIds.putIfAbsent(action.id, anchor.id)
                val levels = buildTargetRangeLevels(frame.nodes, anchor)
                val recommended = levels.getOrNull(recommendTargetIndex(levels)) ?: suggestedNode
                selectedNodeIds.putIfAbsent(action.id, recommended.id)
            }
        }
        val actionIds = actions.mapTo(mutableSetOf()) { it.id }
        enabled.keys.retainAll(actionIds)
        anchorNodeIds.keys.retainAll(actionIds)
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
                title = { Text("检查录制结果") },
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
                    text = if (selectedCount > 1) "$selectedCount 步 · 依次执行" else "$selectedCount 步",
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
                                toast("已加入本地订阅")
                                mainVm.popPage()
                            } catch (e: Exception) {
                                toast("保存失败：${e.message}", forced = true)
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) {
                    Text(if (saving) "保存中" else "完成")
                }
            }
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.scaffoldPadding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (actions.isEmpty()) {
                item {
                    Spacer(Modifier.height(48.dp))
                    EmptyText(text = "没有录制到可处理的操作")
                }
            } else {
                item {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "你刚才做了这些操作",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "点某一步可以检查它；不需要的步骤直接取消勾选。",
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
                            anchorNodeId = anchorNodeIds[action.id],
                            selectedNodeId = selectedNodeId,
                            selectorIndex = selectorIndexes[action.id] ?: 0,
                            onTargetPicked = { anchorId, targetId ->
                                anchorNodeIds[action.id] = anchorId
                                selectedNodeIds[action.id] = targetId
                                selectorIndexes[action.id] = 0
                            },
                            onNodeSelected = { nodeId ->
                                selectedNodeIds[action.id] = nodeId
                                selectorIndexes[action.id] = 0
                            },
                            onSelectorSelected = { index -> selectorIndexes[action.id] = index },
                        )
                    }
                }

                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HorizontalDivider()
                        Text(
                            text = "保存为",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = groupName,
                            onValueChange = { groupName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("名称") },
                        )
                        Text(
                            text = "保存后会按上面的顺序自动执行；这里不需要写规则代码。",
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
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(actions, key = { it.id }) { action ->
            val index = actions.indexOf(action) + 1
            val active = enabled[action.id] == true
            val selected = action.id == selectedActionId
            val shape = RoundedCornerShape(20.dp)
            Card(
                modifier = Modifier
                    .size(width = 148.dp, height = 96.dp)
                    .clickable { onSelect(action.id) }
                    .then(
                        if (selected) {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = shape,
                            )
                        } else Modifier
                    ),
                shape = shape,
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
                            text = "第 $index 步",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = action.text ?: action.desc ?: "点击这里",
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
    anchorNodeId: Int?,
    selectedNodeId: Int?,
    selectorIndex: Int,
    onTargetPicked: (anchorId: Int, targetId: Int) -> Unit,
    onNodeSelected: (Int) -> Unit,
    onSelectorSelected: (Int) -> Unit,
) {
    val frame = action.frame
    val selectedNode = frame?.nodes?.find { it.id == selectedNodeId }
    val fallbackNode = frame?.suggestedNodeId?.let { id -> frame.nodes.find { it.id == id } }
    val highlightedNode = selectedNode ?: fallbackNode
    val anchorNode = frame?.nodes?.find { it.id == anchorNodeId }
        ?: highlightedNode?.let { node -> frame?.let { findFineAnchor(it.nodes, node) } }
    val candidates = RuleRecorder.selectorCandidatesFor(action, highlightedNode?.id)
    val safeSelectorIndex = selectorIndex.coerceIn(0, max(0, candidates.lastIndex))

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "这一步要点哪里？",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "亮框就是 GKD 会寻找并点击的位置。直接点画面可以重新选，下面的范围条可以像元素选择器一样调整大小。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (frame?.screenshotPath != null) {
            ScreenshotElementPicker(
                action = action,
                nodes = frame.nodes,
                screenshotPath = frame.screenshotPath,
                screenWidth = frame.screenWidth,
                screenHeight = frame.screenHeight,
                selectedNode = highlightedNode,
                onAnchorSelected = { anchor ->
                    val levels = buildTargetRangeLevels(frame.nodes, anchor)
                    val target = levels.getOrNull(recommendTargetIndex(levels)) ?: anchor
                    onTargetPicked(anchor.id, target.id)
                },
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (frame == null) {
                            "正在准备刚才的画面…"
                        } else {
                            "这个应用不允许截图，仍可以使用下面的识别方式"
                        },
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (frame != null && anchorNode != null && highlightedNode != null) {
            TargetRangeControl(
                nodes = frame.nodes,
                anchorNode = anchorNode,
                selectedNode = highlightedNode,
                onNodeSelected = onNodeSelected,
            )
        }

        highlightedNode?.let { node ->
            TargetSummaryCard(node = node, action = action)
        }

        Text(
            text = "怎么认出它？",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "一般保持“推荐”即可；只有页面上有很多相似按钮时才需要换更严格的方式。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (candidates.isEmpty()) {
            Text(
                text = "这个位置没有足够稳定的特征。可以点画面里的完整按钮，再把“目标范围”向宽泛方向拖一点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            candidates.forEachIndexed { index, selector ->
                FriendlySelectorChoice(
                    selector = selector,
                    checked = index == safeSelectorIndex,
                    recommended = index == 0,
                    onClick = { onSelectorSelected(index) },
                )
            }
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
    onAnchorSelected: (NodeInfo) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val overlay = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    val ratio = screenWidth.toFloat() / screenHeight.toFloat().coerceAtLeast(1f)
    val painter = rememberAsyncImagePainter(model = screenshotPath)
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .pointerInput(nodes, screenWidth, screenHeight) {
                detectTapGestures { offset ->
                    if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                    val x = offset.x / size.width * screenWidth
                    val y = offset.y / size.height * screenHeight
                    findFineNodeAt(nodes, x, y)?.let(onAnchorSelected)
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
            } else if (screenWidth > 0 && screenHeight > 0) {
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
private fun TargetRangeControl(
    nodes: List<NodeInfo>,
    anchorNode: NodeInfo,
    selectedNode: NodeInfo,
    onNodeSelected: (Int) -> Unit,
) {
    val levels = buildTargetRangeLevels(nodes, anchorNode)
    if (levels.isEmpty()) return
    val recommendedIndex = recommendTargetIndex(levels)
    val selectedIndex = levels.indexOfFirst { it.id == selectedNode.id }
        .takeIf { it >= 0 }
        ?: recommendedIndex
    val current = levels[selectedIndex]
    val atRecommended = selectedIndex == recommendedIndex

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "目标范围",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "拖动时亮框会实时吸附到可用范围",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = targetRangeLabel(current, selectedIndex, levels.size),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            if (levels.size > 1) {
                Slider(
                    value = selectedIndex.toFloat(),
                    onValueChange = { rawValue ->
                        val index = rawValue.roundToInt().coerceIn(levels.indices)
                        val target = levels[index]
                        if (target.id != selectedNode.id) {
                            onNodeSelected(target.id)
                        }
                    },
                    valueRange = 0f..levels.lastIndex.toFloat(),
                    steps = (levels.size - 2).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "更精细",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "向右拖会选择更完整的区域",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "更宽泛",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "这里暂时只有一个合适的点击范围。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (atRecommended) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "已使用推荐范围",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            } else {
                TextButton(
                    onClick = { levels.getOrNull(recommendedIndex)?.let { onNodeSelected(it.id) } },
                ) {
                    Text("恢复推荐范围")
                }
            }
        }
    }
}

@Composable
private fun TargetSummaryCard(node: NodeInfo, action: RecordedRuleAction) {
    val attr = node.attr
    val kind = friendlyTargetKind(node)
    val label = attr.text?.takeIf { it.isNotBlank() }
        ?: attr.desc?.takeIf { it.isNotBlank() }
        ?: action.text
        ?: action.desc
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (label != null) "已选中：$kind「$label」" else "已选中：$kind",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    attr.clickable -> "这是一个可直接点击的区域"
                    attr.text?.isNotBlank() == true -> "这是画面中的文字区域"
                    attr.childCount > 0 -> "这是包含多个内容的区域"
                    else -> "这是画面中的一个元素"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FriendlySelectorChoice(
    selector: String,
    checked: Boolean,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    val display = friendlySelectorDescription(selector)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = display.first,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (recommended) {
                        Text(
                            text = "推荐",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = display.second,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun friendlySelectorDescription(selector: String): Pair<String, String> {
    val hasVid = "[vid=" in selector || ("[id=" in selector && "[vid=" !in selector)
    val hasText = "[text=" in selector
    val hasDesc = "[desc=" in selector
    val hasName = "[name=" in selector
    val count = listOf(hasVid, hasText, hasDesc, hasName).count { it }
    if (count >= 2) {
        return "更严格地认这个目标" to when {
            hasVid && hasText -> "同时确认这个控件本身和它显示的文字，适合有多个相似按钮的页面"
            hasVid && hasDesc -> "同时确认这个控件本身和它的辅助说明，适合图标按钮"
            hasName && hasText -> "同时确认控件类型和显示文字，比只看文字更严格"
            hasName && hasDesc -> "同时确认控件类型和辅助说明"
            else -> "同时使用多个特征，减少误点相似元素"
        }
    }
    return when {
        hasVid -> "认准这个控件" to "使用应用给这个控件的固定标识，通常最稳定"
        hasText -> "认准这段文字" to "根据屏幕上显示的文字寻找；文字变化或切换语言时可能失效"
        hasDesc -> "认准它的辅助说明" to "适合没有文字的图标、按钮等目标"
        hasName -> "认准这类控件" to "范围比较宽，页面上有多个同类控件时不建议单独使用"
        else -> "使用当前特征" to "使用录制时发现的特征寻找目标"
    }
}

private fun friendlyTargetKind(node: NodeInfo): String {
    val attr = node.attr
    val shortName = attr.name?.substringAfterLast('.').orEmpty()
    return when {
        shortName.contains("Button", ignoreCase = true) -> "按钮"
        attr.clickable -> "可点击区域"
        shortName.contains("Image", ignoreCase = true) -> "图片"
        attr.text?.isNotBlank() == true -> "文字"
        attr.childCount > 0 -> "区域"
        else -> "目标"
    }
}

private fun targetRangeLabel(node: NodeInfo, index: Int, count: Int): String {
    val attr = node.attr
    val shortName = attr.name?.substringAfterLast('.').orEmpty()
    return when {
        shortName.contains("Button", ignoreCase = true) || attr.clickable -> "整个按钮"
        attr.text?.isNotBlank() == true && attr.childCount == 0 -> "文字 / 图标"
        index == 0 -> "最精细"
        index == count - 1 -> "整块区域"
        attr.childCount > 0 -> "内容区域"
        else -> "目标区域"
    }
}

private fun hasUsefulIdentity(node: NodeInfo): Boolean {
    val a = node.attr
    return !a.vid.isNullOrBlank() || !a.id.isNullOrBlank() ||
        !a.text.isNullOrBlank() || !a.desc.isNullOrBlank()
}

private fun hasVisualSignal(node: NodeInfo): Boolean {
    val a = node.attr
    return hasUsefulIdentity(node) || a.clickable || a.childCount == 0
}

private fun nodeContains(node: NodeInfo, x: Float, y: Float): Boolean {
    val a = node.attr
    return x >= a.left && x <= a.right && y >= a.top && y <= a.bottom
}

private fun nodeArea(node: NodeInfo): Long {
    val a = node.attr
    return (a.right - a.left).coerceAtLeast(1).toLong() *
        (a.bottom - a.top).coerceAtLeast(1).toLong()
}

private fun sameBounds(first: NodeInfo, second: NodeInfo): Boolean {
    val a = first.attr
    val b = second.attr
    return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom
}

private fun findFineNodeAt(nodes: List<NodeInfo>, x: Float, y: Float): NodeInfo? {
    val underFinger = nodes.asSequence()
        .filter { it.attr.visibleToUser }
        .filter { it.attr.width > 0 && it.attr.height > 0 }
        .filter { nodeContains(it, x, y) }
        .toList()
    if (underFinger.isEmpty()) return null

    return underFinger
        .filter(::hasVisualSignal)
        .minWithOrNull(
            compareBy<NodeInfo>(::nodeArea).thenByDescending { it.attr.depth }
        )
        ?: underFinger.minWithOrNull(
            compareBy<NodeInfo>(::nodeArea).thenByDescending { it.attr.depth }
        )
}

private fun findFineAnchor(nodes: List<NodeInfo>, selected: NodeInfo): NodeInfo {
    val byId = nodes.associateBy { it.id }
    return nodes.asSequence()
        .filter { it.id != selected.id }
        .filter { it.attr.visibleToUser && it.attr.width > 0 && it.attr.height > 0 }
        .filter(::hasVisualSignal)
        .filter { isDescendantOf(it, selected.id, byId) }
        .sortedWith(
            compareByDescending<NodeInfo> { it.attr.depth }
                .thenBy { nodeArea(it) }
        )
        .firstOrNull()
        ?: selected
}

private fun buildTargetRangeLevels(nodes: List<NodeInfo>, anchor: NodeInfo): List<NodeInfo> {
    val byId = nodes.associateBy { it.id }
    val chain = mutableListOf<NodeInfo>()
    var current: NodeInfo? = anchor
    var guard = 0
    while (current != null && guard < 24) {
        if (
            current.attr.visibleToUser &&
            current.attr.width > 0 &&
            current.attr.height > 0 &&
            chain.none { sameBounds(it, current!!) }
        ) {
            chain += current
        }
        current = current.pid.takeIf { it >= 0 }?.let(byId::get)
        guard++
    }
    return chain.take(9)
}

private fun recommendTargetIndex(levels: List<NodeInfo>): Int {
    if (levels.isEmpty()) return 0
    val stableClickable = levels.indexOfFirst { node ->
        val a = node.attr
        a.clickable && (!a.vid.isNullOrBlank() || !a.id.isNullOrBlank())
    }
    if (stableClickable >= 0) return stableClickable

    val identifiableClickable = levels.indexOfFirst { it.attr.clickable && hasUsefulIdentity(it) }
    if (identifiableClickable >= 0) return identifiableClickable

    val stable = levels.indexOfFirst { node ->
        !node.attr.vid.isNullOrBlank() || !node.attr.id.isNullOrBlank()
    }
    if (stable >= 0) return stable

    val identifiable = levels.indexOfFirst(::hasUsefulIdentity)
    if (identifiable >= 0) return identifiable

    return (levels.size / 2).coerceIn(levels.indices)
}

private fun isDescendantOf(
    node: NodeInfo,
    ancestorId: Int,
    byId: Map<Int, NodeInfo>,
): Boolean {
    var parentId = node.pid
    var guard = 0
    while (parentId >= 0 && guard < 32) {
        if (parentId == ancestorId) return true
        parentId = byId[parentId]?.pid ?: return false
        guard++
    }
    return false
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
            require(selector.isNotBlank()) { "步骤 ${index + 1} 没有可用的识别方式" }
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
