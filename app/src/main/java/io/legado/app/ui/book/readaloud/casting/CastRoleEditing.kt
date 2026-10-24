package io.legado.app.ui.book.readaloud.casting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.help.readaloud.cast.BookCastStore
import io.legado.app.help.readaloud.cast.VoiceAudition
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.OptionDropdownCard
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.MenuItemIcon
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.rememberVoiceAuditionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 官方人物卡片行右侧的动作菜单：展开行内编辑、设这个角色自己的气泡、换头像、删除这个角色。
 *
 * 卡片本身点下去是官方的人物详情页。这里只补官方没有的几件事——改名字/声音池、
 * 不设进详情页就能换头像，以及把角色连同它的分配句一起删掉，样式沿用官方书源列表
 * 那套「更多」溢出菜单（`BookSourceItemMenu`），不在卡片上摆两个裸图标。
 */
@Composable
fun CastRoleRowActions(
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetAvatar: () -> Unit = {},
    onSetBubble: () -> Unit = {},
    hasBubble: Boolean = false,
    onClearBubble: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        SmallPlainButton(
            icon = AppIcons.MoreVert,
            contentDescription = stringResource(R.string.menu),
            selected = editing,
            onClick = { expanded = true },
        )
        RoundDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) { dismiss ->
            RoundDropdownMenuItem(
                text = stringResource(R.string.edit),
                leadingIcon = {
                    MenuItemIcon(
                        imageVector = AppIcons.Edit,
                        contentDescription = null,
                    )
                },
                onClick = {
                    dismiss()
                    onToggleEdit()
                },
            )
            RoundDropdownMenuItem(
                text = stringResource(R.string.character_avatar_source),
                leadingIcon = {
                    MenuItemIcon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                    )
                },
                onClick = {
                    dismiss()
                    onSetAvatar()
                },
            )
            RoundDropdownMenuItem(
                text = stringResource(R.string.cast_bubble_menu),
                leadingIcon = {
                    MenuItemIcon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                    )
                },
                onClick = {
                    dismiss()
                    onSetBubble()
                },
            )
            // 弹层里已经没有「启用」那一栏了，撤掉气泡只能在菜单里做一次
            if (hasBubble) {
                RoundDropdownMenuItem(
                    text = stringResource(R.string.cast_bubble_clear),
                    leadingIcon = {
                        MenuItemIcon(
                            imageVector = AppIcons.Close,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        dismiss()
                        onClearBubble()
                    },
                )
            }
            RoundDropdownMenuItem(
                text = stringResource(R.string.cast_delete_character),
                color = LegadoTheme.colorScheme.error,
                leadingIcon = {
                    MenuItemIcon(
                        imageVector = AppIcons.Delete,
                        contentDescription = null,
                        tint = LegadoTheme.colorScheme.error,
                    )
                },
                onClick = {
                    dismiss()
                    onDelete()
                },
            )
        }
    }
}

/**
 * 草稿角色（AI 认出、还没进正式角色卡）行右侧的动作菜单：转正、编辑音色。
 *
 * 转正这个动作 ViewModel 早就有实现，界面上一直没有入口——于是这些说话人只能永远停在草稿：
 * 换头像、设气泡、进人物详情都只对正式角色开放，用户看着就是"这个名字我改不了"。
 * 入口挂在本页已有的「更多」菜单里，不新加按钮也不新浮层，与正式角色行同一份语法。
 */
@Composable
fun CastDraftRowActions(
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onPromote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        SmallPlainButton(
            icon = AppIcons.MoreVert,
            contentDescription = stringResource(R.string.menu),
            selected = editing,
            onClick = { expanded = true },
        )
        RoundDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) { dismiss ->
            RoundDropdownMenuItem(
                text = stringResource(R.string.voice_promote_action),
                leadingIcon = {
                    MenuItemIcon(
                        imageVector = AppIcons.Check,
                        contentDescription = null,
                    )
                },
                onClick = {
                    dismiss()
                    onPromote()
                },
            )
            RoundDropdownMenuItem(
                text = stringResource(R.string.edit),
                leadingIcon = {
                    MenuItemIcon(
                        imageVector = AppIcons.Edit,
                        contentDescription = null,
                    )
                },
                onClick = {
                    dismiss()
                    onToggleEdit()
                },
            )
        }
    }
}

/**
 * 角色编辑对话框：声音池 / 音色 / 变声器三张纯选择下拉卡（[OptionDropdownCard]，
 * 与人物详情弹窗同款），点开只弹选项菜单、不弹输入法。
 *
 * **没有名字栏**：改名去官方的人物详情页（那里带别名与档案联动），这里只管「怎么发声」。
 * 保存走 [BookCastStore.updateCharacter]：它会把池同步进官方人物档案、改写所有分配句，
 * 并把选中的音色锁进 book_voice_bindings（发音链路读它）。
 * 变声器那一栏存的是**角色级**的 cast_characters.voiceEffect（全局，这个角色的所有句子都用它）。
 *
 * 标题就是人物名（弹窗从列表行进来，靠名字确认在改谁）。
 * 底部动作条交给 [AppAlertDialog] 自己排：试听（试听的是**未保存的草稿值**）在最左，
 * 取消、保存跟上，间距与项目弹窗同一套规范，不在 content 里手画按钮。
 */
@Composable
fun CastRoleEditorDialog(
    bookUrl: String,
    item: VoiceCastingItemUi?,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 常驻组合、只把 item 置 null：miuix 的窗口靠 show 驱动退场动画，条件组合会连动画一起摘掉
    var cached by remember { mutableStateOf(item) }
    if (item != null) cached = item
    val current = cached ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bundle by produceState<BookCastStore.EditBundle?>(initialValue = null, current.subjectId) {
        value = withContext(Dispatchers.IO) { BookCastStore.editBundle(bookUrl) }
    }
    val data = bundle ?: return
    var pool by remember(current.subjectId) { mutableStateOf(current.poolLabel) }
    var voiceId by remember(current.subjectId) { mutableStateOf(current.voiceId) }
    var voiceEffect by remember(current.subjectId) { mutableStateOf(current.voiceEffect) }
    // 池决定音色候选：指了池就只摆池内音色，换池时清掉不在新池里的旧选择
    val poolVoices = remember(data.voices, pool) {
        data.voices.filter { pool.isBlank() || it.poolNames.contains(pool.trim()) }
    }
    val unspecified = stringResource(R.string.cast_option_unspecified)
    val autoVoice = stringResource(R.string.cast_option_auto_voice)
    val noEffect = stringResource(R.string.cast_option_no_effect)
    fun voiceLabel(id: String): String =
        if (id.isBlank()) autoVoice
        else data.voices.firstOrNull { it.id == id }?.displayName ?: unspecified
    val audition = rememberVoiceAuditionController(
        voiceId = voiceId,
        text = remember(context) { VoiceAudition.defaultPreviewText(context) },
        effect = voiceEffect,
    )
    AppAlertDialog(
        show = item != null,
        onDismissRequest = onDismiss,
        title = current.name.ifBlank { stringResource(R.string.edit) },
        startActionText = stringResource(R.string.cloud_tts_preview),
        onStartAction = audition.toggle,
        startActionEnabled = audition.enabled,
        confirmText = stringResource(R.string.save),
        onConfirm = {
            scope.launch(Dispatchers.IO) {
                val ok = BookCastStore.updateCharacter(
                    bookUrl = bookUrl,
                    characterId = current.subjectId,
                    // 名字原样带回：本弹窗不改名（改名去人物详情页）
                    name = current.name,
                    poolLabel = pool,
                    voiceId = voiceId,
                    voiceEffect = voiceEffect,
                )
                if (ok) {
                    // 正文重排在 updateCharacter 里按「改的是不是正文看得见的那几栏」决定
                    withContext(Dispatchers.Main) { onSaved() }
                }
            }
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionDropdownCard(
                    label = stringResource(R.string.cast_voice_pool),
                    text = pool.ifBlank { unspecified },
                    options = listOf("") + data.pools,
                    optionLabel = { it.ifBlank { unspecified } },
                    onOptionClick = { picked ->
                        pool = picked
                        if (voiceId.isNotBlank() &&
                            data.voices.firstOrNull { it.id == voiceId }
                                ?.poolNames?.contains(picked.trim()) != true
                        ) {
                            voiceId = ""
                        }
                    },
                    isSelected = { it == pool },
                )
                OptionDropdownCard(
                    label = stringResource(R.string.cast_voice),
                    text = voiceLabel(voiceId),
                    options = listOf("") + poolVoices.map { it.id },
                    optionLabel = { voiceLabel(it) },
                    onOptionClick = { voiceId = it },
                    isSelected = { it == voiceId },
                )
                OptionDropdownCard(
                    label = stringResource(R.string.cast_voice_effect_global),
                    text = voiceEffect.ifBlank { noEffect },
                    options = listOf("") + data.effects,
                    optionLabel = { it.ifBlank { noEffect } },
                    onOptionClick = { voiceEffect = it },
                    isSelected = { it == voiceEffect },
                )
            }
        },
    )
}

/**
 * 单音色编辑对话框：只有一行「音色」+ 试听，与角色编辑同一套弹窗组件。
 *
 * 标题用行主体自己的叫法（[subjectTitle]）：草稿角色给名字，旁白/未知角色给文案，
 * 不再是一句谁都不认得的「编辑」。底部动作条与角色编辑同一套：试听在最左，取消、保存跟上。
 *
 * 它服务两类行，两类的有效状态都只是 book_voice_bindings 里的一条：
 * - 旁白：不是角色，没有名字、不进声音池、也没有角色级变声器那一列可写
 *   （那些都存在 cast_characters 上，旁白在表里没有行），所以只摆真正生效的那一件事。
 * - 草稿角色（AI 认出但还没转正）：能定音色，名字和池还没到可编辑的时候。
 *
 * 保存只交 voiceId，**主体由调用方在那一行上带下去**（旁白是 (narrator, narrator)，
 * 草稿角色是 (character, 档案 id)），别在这里认死旁白。
 * 选空值等于删掉这条绑定：旁白回到朗读引擎自己的默认发音人，草稿角色回到自动选音。
 */
@Composable
fun CastVoiceRowEditorDialog(
    item: VoiceCastingItemUi?,
    voices: List<VoiceOptionUi>,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 常驻组合缓存最后一份，退场动画期间仍有内容可画（同 [CastRoleEditorDialog]）
    var cached by remember { mutableStateOf(item) }
    if (item != null) cached = item
    val current = cached ?: return
    val context = LocalContext.current
    var voiceId by remember(current.subjectId) { mutableStateOf(current.voiceId) }
    // 空值那一栏对两类行说的不是同一件事，文案按行主体分开
    val defaultLabel = stringResource(
        if (current.subjectType == BookVoiceBinding.SUBJECT_CHARACTER) {
            R.string.cast_character_voice_default
        } else {
            R.string.cast_narrator_voice_default
        }
    )
    val options = remember(voices, defaultLabel) {
        listOf("") + voices.filter { it.selectable }.map { it.id }
    }
    fun voiceLabel(id: String): String = when {
        id.isBlank() -> defaultLabel
        else -> voices.firstOrNull { it.id == id }
            ?.let { listOf(it.name, it.engineName).filter(String::isNotBlank).joinToString(" · ") }
            ?: defaultLabel
    }
    val audition = rememberVoiceAuditionController(
        voiceId = voiceId,
        text = remember(context) { VoiceAudition.defaultPreviewText(context) },
    )
    AppAlertDialog(
        show = item != null,
        onDismissRequest = onDismiss,
        title = subjectTitle(current.kind, current.name).ifBlank {
            stringResource(R.string.edit)
        },
        startActionText = stringResource(R.string.cloud_tts_preview),
        onStartAction = audition.toggle,
        startActionEnabled = audition.enabled,
        confirmText = stringResource(R.string.save),
        onConfirm = { onSave(voiceId) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionDropdownCard(
                    label = stringResource(R.string.cast_voice),
                    text = voiceLabel(voiceId),
                    options = options,
                    optionLabel = { voiceLabel(it) },
                    onOptionClick = { voiceId = it },
                    isSelected = { it == voiceId },
                )
            }
        },
    )
}

/** 删除确认：连分配句、音色绑定、人物档案与本书记忆里的那一行一起清掉。 */
@Composable
fun CastRoleDeleteDialog(
    bookUrl: String,
    target: VoiceCastingItemUi?,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    AppAlertDialog(
        show = target != null,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.cast_delete_character),
        text = target?.let { stringResource(R.string.cast_delete_character_confirm, it.name) },
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onDismiss = onDismiss,
        onConfirm = {
            val doomed = target ?: return@AppAlertDialog
            onDismiss()
            scope.launch(Dispatchers.IO) {
                BookCastStore.deleteCharacter(bookUrl, doomed.subjectId, doomed.name)
                BookCastStore.reloadReaderChapter(bookUrl)
                withContext(Dispatchers.Main) { onDeleted() }
            }
        },
    )
}
