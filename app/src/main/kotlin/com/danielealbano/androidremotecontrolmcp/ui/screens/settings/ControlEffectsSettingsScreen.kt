@file:Suppress("FunctionNaming", "LongParameterList")

package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEdgeColorMode
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEffectsConfig
import com.danielealbano.androidremotecontrolmcp.services.control.ControlEffectsView
import com.danielealbano.androidremotecontrolmcp.ui.components.OrulinkSectionHeader
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ControlEffectsViewModel
import kotlin.math.roundToInt

private const val PAGE_PADDING_DP = 16
private const val SECTION_GAP_DP = 24
private const val CONTENT_GAP_DP = 12
private const val PREVIEW_WIDTH_DP = 108
private const val PREVIEW_HEIGHT_DP = 216
private const val PREVIEW_BASE_WIDTH_DP = 360
private const val PREVIEW_BASE_HEIGHT_DP = 720
private const val PREVIEW_SCALE = 0.3f
private const val PREVIEW_CORNER_DP = 24
private const val SWATCH_SIZE_DP = 16
private const val PERCENT_SCALE = 100
private const val SWATCH_BLUE_ARGB = 0xFF3380FF.toInt()
private const val SWATCH_CYAN_ARGB = 0xFF23BCD4.toInt()
private const val SWATCH_VIOLET_ARGB = 0xFF8964EC.toInt()
private const val SWATCH_PINK_ARGB = 0xFFE563A5.toInt()
private const val SWATCH_MINT_ARGB = 0xFF35BC91.toInt()
private const val SWATCH_ORANGE_ARGB = 0xFFEA9140.toInt()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlEffectsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ControlEffectsViewModel = hiltViewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
    val selectConfig: (ControlEffectsConfig) -> Unit = {
        viewModel.preview(it)
        viewModel.savePreview()
    }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.control_effects_settings_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.control_effects_back),
                    )
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(PAGE_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
        ) {
            ControlEffectsPreview(config)
            ControlEffectsColorSettings(config, selectConfig)
            ControlEffectsAdjustments(config, viewModel::preview, viewModel::savePreview)
            if (saveFailed) ControlEffectsSaveError(viewModel::savePreview)
            TextButton(onClick = viewModel::restoreDefaults) {
                Text(stringResource(R.string.control_effects_restore_defaults))
            }
        }
    }
}

@Composable
private fun ControlEffectsPreview(config: ControlEffectsConfig) {
    val description = stringResource(R.string.control_effects_preview_description)
    Column(verticalArrangement = Arrangement.spacedBy(CONTENT_GAP_DP.dp)) {
        OrulinkSectionHeader(
            title = stringResource(R.string.control_effects_preview),
            subtitle = stringResource(R.string.control_effects_preview_hint),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(PREVIEW_CORNER_DP.dp))
                .padding(CONTENT_GAP_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(PREVIEW_WIDTH_DP.dp, PREVIEW_HEIGHT_DP.dp)
                    .clip(RoundedCornerShape(PREVIEW_CORNER_DP.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(PREVIEW_CORNER_DP.dp))
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { context -> ControlEffectsView(context).apply { isActive = true } },
                    update = { it.config = config },
                    modifier =
                        Modifier
                            .requiredSize(PREVIEW_BASE_WIDTH_DP.dp, PREVIEW_BASE_HEIGHT_DP.dp)
                            .graphicsLayer(scaleX = PREVIEW_SCALE, scaleY = PREVIEW_SCALE),
                )
            }
        }
    }
}

@Composable
private fun ControlEffectsColorSettings(
    config: ControlEffectsConfig,
    onSelect: (ControlEffectsConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CONTENT_GAP_DP.dp)) {
        OrulinkSectionHeader(title = stringResource(R.string.control_effects_color_title))
        Card {
            Column(Modifier.selectableGroup()) {
                ControlEdgeColorMode.entries.forEach { mode ->
                    val selected = config.colorMode == mode
                    ListItem(
                        headlineContent = { Text(stringResource(mode.titleResource)) },
                        supportingContent = { Text(stringResource(mode.descriptionResource)) },
                        leadingContent = { RadioButton(selected = selected, onClick = null) },
                        modifier =
                            Modifier.selectable(selected, role = Role.RadioButton) {
                                onSelect(config.copy(colorMode = mode))
                            },
                    )
                }
            }
        }
        if (config.colorMode == ControlEdgeColorMode.CUSTOM) {
            ControlEffectsCustomColor(config, onSelect)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlEffectsCustomColor(
    config: ControlEffectsConfig,
    onSelect: (ControlEffectsConfig) -> Unit,
) {
    var input by remember(config.customColorArgb) { mutableStateOf(formatControlEffectsColor(config.customColorArgb)) }
    val parsed = parseControlEffectsColor(input)
    Column(verticalArrangement = Arrangement.spacedBy(CONTENT_GAP_DP.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CONTENT_GAP_DP.dp)) {
            controlEffectsSwatches.forEach { (color, name) ->
                FilterChip(
                    selected = config.customColorArgb == color,
                    onClick = { onSelect(config.copy(customColorArgb = color)) },
                    label = { Text(stringResource(name)) },
                    leadingIcon = {
                        Box(Modifier.size(SWATCH_SIZE_DP.dp).background(Color(color), CircleShape))
                    },
                )
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.control_effects_hex_label)) },
            supportingText = {
                Text(
                    stringResource(
                        if (parsed == null) R.string.control_effects_hex_error else R.string.control_effects_hex_hint,
                    ),
                )
            },
            isError = parsed == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = { parsed?.let { onSelect(config.copy(customColorArgb = it)) } },
            enabled = parsed != null,
        ) {
            Text(stringResource(R.string.control_effects_apply_color))
        }
    }
}

@Composable
private fun ControlEffectsAdjustments(
    config: ControlEffectsConfig,
    onPreview: (ControlEffectsConfig) -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CONTENT_GAP_DP.dp)) {
        OrulinkSectionHeader(title = stringResource(R.string.control_effects_adjustments))
        Card {
            Column(
                Modifier.padding(PAGE_PADDING_DP.dp),
                verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
            ) {
                ControlEffectsRangeAndDepth(config, onPreview, onSave)
                ControlEffectsMotionSettings(config, onPreview, onSave)
            }
        }
    }
}

@Composable
private fun ControlEffectsRangeAndDepth(
    config: ControlEffectsConfig,
    onPreview: (ControlEffectsConfig) -> Unit,
    onSave: () -> Unit,
) {
    ControlEffectSlider(
        title = R.string.control_effects_width,
        hint = R.string.control_effects_width_hint,
        value = config.glowWidthDp,
        displayValue =
            stringResource(
                R.string.control_effects_width_value,
                config.glowWidthDp.roundToInt(),
            ),
        range = ControlEffectsConfig.MIN_GLOW_WIDTH_DP..ControlEffectsConfig.MAX_GLOW_WIDTH_DP,
        onChange = { onPreview(config.copy(glowWidthDp = it)) },
        onFinished = onSave,
    )
    ControlEffectSlider(
        title = R.string.control_effects_intensity,
        hint = R.string.control_effects_intensity_hint,
        value = config.intensity,
        displayValue = effectPercent(config.intensity),
        range = ControlEffectsConfig.MIN_INTENSITY..ControlEffectsConfig.MAX_INTENSITY,
        onChange = { onPreview(config.copy(intensity = it)) },
        onFinished = onSave,
    )
}

@Composable
private fun ControlEffectsMotionSettings(
    config: ControlEffectsConfig,
    onPreview: (ControlEffectsConfig) -> Unit,
    onSave: () -> Unit,
) {
    ControlEffectSlider(
        title = R.string.control_effects_motion,
        hint = R.string.control_effects_motion_hint,
        value = config.motionRange,
        displayValue = effectPercent(config.motionRange),
        range = ControlEffectsConfig.MIN_MOTION_RANGE..ControlEffectsConfig.MAX_MOTION_RANGE,
        onChange = { onPreview(config.copy(motionRange = it)) },
        onFinished = onSave,
    )
    ControlEffectSlider(
        title = R.string.control_effects_speed,
        hint = R.string.control_effects_speed_hint,
        value = config.speed,
        displayValue = stringResource(R.string.control_effects_speed_value, config.speed),
        range = ControlEffectsConfig.MIN_SPEED..ControlEffectsConfig.MAX_SPEED,
        onChange = { onPreview(config.copy(speed = it)) },
        onFinished = onSave,
    )
}

@Composable
private fun ControlEffectSlider(
    title: Int,
    hint: Int,
    value: Float,
    displayValue: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val label = stringResource(title)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CONTENT_GAP_DP.dp),
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(displayValue, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            modifier =
                Modifier.semantics {
                    contentDescription = label
                    stateDescription = displayValue
                },
        )
    }
}

@Composable
private fun effectPercent(value: Float): String =
    stringResource(R.string.control_effects_percent_value, (value * PERCENT_SCALE).roundToInt())

@Composable
private fun ControlEffectsSaveError(onRetry: () -> Unit) {
    Column {
        Text(stringResource(R.string.control_effects_save_error), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.control_effects_retry)) }
    }
}

private val ControlEdgeColorMode.titleResource: Int
    get() =
        when (this) {
            ControlEdgeColorMode.MULTICOLOR -> R.string.control_effects_multicolor
            ControlEdgeColorMode.BLUE -> R.string.control_effects_blue
            ControlEdgeColorMode.CUSTOM -> R.string.control_effects_custom
        }

private val ControlEdgeColorMode.descriptionResource: Int
    get() =
        when (this) {
            ControlEdgeColorMode.MULTICOLOR -> R.string.control_effects_multicolor_hint
            ControlEdgeColorMode.BLUE -> R.string.control_effects_blue_hint
            ControlEdgeColorMode.CUSTOM -> R.string.control_effects_custom_hint
        }

private val controlEffectsSwatches =
    listOf(
        SWATCH_BLUE_ARGB to R.string.control_effects_swatch_blue,
        SWATCH_CYAN_ARGB to R.string.control_effects_swatch_cyan,
        SWATCH_VIOLET_ARGB to R.string.control_effects_swatch_violet,
        SWATCH_PINK_ARGB to R.string.control_effects_swatch_pink,
        SWATCH_MINT_ARGB to R.string.control_effects_swatch_mint,
        SWATCH_ORANGE_ARGB to R.string.control_effects_swatch_orange,
    )
