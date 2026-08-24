package li.songe.gkd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import li.songe.gkd.data.SubscriptionInputParser
import li.songe.gkd.data.edit
import li.songe.gkd.service.RecordedRuleAction
import li.songe.gkd.service.RuleRecorder
import li.songe.gkd.ui.component.EmptyText
import li.songe.gkd.ui.component.PerfIcon
import li.songe.gkd.ui.component.PerfIconButton
import li.songe.gkd.ui.component.PerfTopAppBar
import li.songe.gkd.ui.share.LocalMainViewModel
import li.songe.gkd.ui.style.itemPadding
import li.songe.gkd.ui.style.scaffoldPadding
import li.songe.gkd.util.LOCAL_SUBS_ID
import li.songe.gkd.util.SubscriptionStore
import li.songe.gkd.util.toast

@Serializable
data object RuleRecorderRoute : NavKey

@Composable
fun RuleRecorderPage() {
    val mainVm = LocalMainViewModel.current
    val actions by RuleRecorder.actionsFlow.collectAsStateWithLifecycle()
    val enabled = remember { mutableStateMapOf<Long, Boolean>() }
    val selectorIndexes = remember { mutableStateMapOf<Long, Int>() }
    var groupName by remember {
        mutableStateOf("录制规则-${System.currentTimeMillis().toString().takeLast(6)}")
    }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(actions) {
        actions.forEach { action ->
            enabled.putIfAbsent(action.id, action.hasStableSelector)
            selectorIndexes.putIfAbsent(action.id, 0)
        }
        enabled.keys.retainAll(actions.mapTo(mutableSetOf()) { it.id })
        selectorIndexes.keys.retainAll(actions.mapTo(mutableSetOf()) { it.id })
    }

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
                title = { Text("处理录制规则") },
                actions = {
                    TextButton(
                        onClick = {
                            enabled.keys.toList().forEach { enabled[it] = false }
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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已选 ${actions.count { enabled[it.id] == true }} / ${actions.size}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    enabled = !saving && actions.any { enabled[it.id] == true } && groupName.isNotBlank(),
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                saveRecordedRules(
                                    actions = actions,
                                    enabled = enabled,
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
                    Text(if (saving) "保存中" else "加入本地订阅")
                }
            }
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.scaffoldPadding(contentPadding),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "勾选要生成规则的点击；点击选择器可在 vid / id / text / desc / 控件类型之间切换。保存时会按应用自动拆分规则组。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("规则组名称") },
                    )
                }
            }
            items(actions, key = { it.id }) { action ->
                RuleRecorderActionRow(
                    action = action,
                    checked = enabled[action.id] == true,
                    selectorIndex = selectorIndexes[action.id] ?: 0,
                    onCheckedChange = { enabled[action.id] = it },
                    onCycleSelector = {
                        if (action.selectorCandidates.isNotEmpty()) {
                            selectorIndexes[action.id] =
                                ((selectorIndexes[action.id] ?: 0) + 1) % action.selectorCandidates.size
                        }
                    },
                )
            }
            if (actions.isEmpty()) {
                item {
                    Spacer(Modifier.height(48.dp))
                    EmptyText(text = "没有录制到可处理的点击")
                }
            }
        }
    }
}

@Composable
private fun RuleRecorderActionRow(
    action: RecordedRuleAction,
    checked: Boolean,
    selectorIndex: Int,
    onCheckedChange: (Boolean) -> Unit,
    onCycleSelector: () -> Unit,
) {
    val selector = action.selectorCandidates.getOrNull(selectorIndex).orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .itemPadding(),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = action.appId,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val label = action.text ?: action.desc ?: action.viewId ?: action.className ?: "点击节点"
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = selector,
                modifier = Modifier.clickable(onClick = onCycleSelector),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (action.selectorCandidates.size > 1) {
                Text(
                    text = "选择器 ${selectorIndex + 1}/${action.selectorCandidates.size} · 点此切换",
                    modifier = Modifier.clickable(onClick = onCycleSelector),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private suspend fun saveRecordedRules(
    actions: List<RecordedRuleAction>,
    enabled: Map<Long, Boolean>,
    selectorIndexes: Map<Long, Int>,
    groupName: String,
) {
    val selected = actions.filter { enabled[it.id] == true }
    require(selected.isNotEmpty()) { "至少选择一个点击" }

    selected.groupBy { it.appId }.forEach { (appId, appActions) ->
        val rules = appActions.joinToString(",\n") { action ->
            val index = selectorIndexes[action.id] ?: 0
            val selector = action.selectorCandidates.getOrNull(index)
                ?: action.defaultSelector
            require(selector.isNotBlank()) { "存在空选择器" }
            "{ matches: [${quoteJson5(selector)}] }"
        }
        val input = SubscriptionInputParser.parse(
            """
            {
              name: ${quoteJson5(groupName)},
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
