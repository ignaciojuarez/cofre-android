package com.ignacio.cofre.ui.setup

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.design.AppIcon
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.CenteredCard
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ControllerHintBar
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.Meta
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.ScreenFocus
import com.ignacio.cofre.design.StepBar
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.runtime.JreManager
import com.ignacio.cofre.runtime.VulkanReport
import com.ignacio.cofre.ui.common.Fmt
import com.ignacio.cofre.ui.common.Note
import com.ignacio.cofre.ui.settings.AccountsPanel

/** First run on Android: Welcome → Checking your device → Sign in → Ready. */
enum class SetupStep(@param:StringRes val title: Int) {
    Welcome(R.string.setup_step_welcome),
    Device(R.string.setup_step_device),
    SignIn(R.string.setup_step_account),
    Ready(R.string.setup_step_ready),
}

/** What the device check found: Vulkan from the driver, Java from the APK, free space from the data partition. */
data class DeviceReadiness(val vulkan: VulkanReport, val javaBundled: Boolean, val freeBytes: Long) {
    val storageOk: Boolean get() = freeBytes >= NEEDED_BYTES

    companion object {
        /** The game, NeoForge, Java and a few mods, with room to spare. */
        const val NEEDED_BYTES: Long = 2L * 1024 * 1024 * 1024

        fun of(graph: AppGraph): DeviceReadiness = DeviceReadiness(
            graph.vulkan,
            runCatching { JreManager(graph.context).bundledVersion() != null }.getOrDefault(false),
            graph.home.usableSpace,
        )
    }
}

@Composable
fun SetupFlow(
    graph: AppGraph,
    modifier: Modifier = Modifier,
    start: SetupStep = SetupStep.Welcome,
    readiness: DeviceReadiness = remember { DeviceReadiness.of(graph) },
) {
    var step by rememberSaveable { mutableStateOf(start) }
    val done = { graph.settings.update { it.copy(setupDone = true) } }
    BackHandler(step != SetupStep.Welcome) { step = SetupStep.entries[step.ordinal - 1] }
    Column(modifier.fillMaxSize()) {
        // Each step is its own screen, so focus lands on its first button.
        key(step) {
            ScreenFocus(Modifier.weight(1f)) {
                when (step) {
                    SetupStep.Welcome -> Welcome(onNext = { step = SetupStep.Device })
                    SetupStep.Device -> DeviceCheck(readiness, onNext = { step = SetupStep.SignIn })
                    SetupStep.SignIn -> SignInStep(graph, onNext = { step = SetupStep.Ready })
                    SetupStep.Ready -> Ready(readiness, signedIn = graph.accounts.ready, onDone = done)
                }
            }
        }
        ControllerHintBar()
    }
}

@Composable
private fun Steps(current: SetupStep) {
    StepBar(SetupStep.entries.map { stringResource(it.title) }, current.ordinal)
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    CenteredCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(size = 48.dp)
            CofreText(stringResource(R.string.setup_welcome_title), CofreTheme.type.title)
        }
        CofreText(stringResource(R.string.setup_welcome_body), CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
        Steps(SetupStep.Welcome)
        BlockButton(stringResource(R.string.setup_get_started), onNext, Modifier.testTag("setup.next"), role = ButtonRole.Primary, size = ButtonSize.Large)
    }
}

@Composable
private fun DeviceCheck(readiness: DeviceReadiness, onNext: () -> Unit) {
    if (!readiness.vulkan.suitable) {
        DeviceUnsuitable(readiness, onContinue = onNext)
        return
    }
    val context = LocalContext.current
    CenteredCard {
        CofreText(stringResource(R.string.setup_device_title), CofreTheme.type.title)
        Steps(SetupStep.Device)
        ListSection {
            CheckRow(stringResource(R.string.setup_vulkan), "${readiness.vulkan.deviceName} · Vulkan ${readiness.vulkan.apiVersion}", ok = true)
            CheckRow(stringResource(R.string.setup_java), stringResource(if (readiness.javaBundled) R.string.setup_java_ready else R.string.setup_java_missing), readiness.javaBundled)
            CheckRow(
                stringResource(R.string.setup_storage),
                stringResource(R.string.setup_storage_free, Fmt.bytes(context, readiness.freeBytes)),
                readiness.storageOk,
            )
        }
        if (!readiness.storageOk) Note(stringResource(R.string.setup_storage_low, Fmt.bytes(context, DeviceReadiness.NEEDED_BYTES)))
        BlockButton(stringResource(R.string.setup_continue), onNext, Modifier.testTag("setup.next"), role = ButtonRole.Primary, size = ButtonSize.Large)
    }
}

/**
 * The friendly "can't run" screen: what's missing in plain words, and what Cofre still does here. Continue goes on
 * with setup, since instances, mods and worlds still work and can move to a device that runs the game.
 */
@Composable
fun DeviceUnsuitable(
    readiness: DeviceReadiness,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    CenteredCard(modifier.testTag("setup.unsuitable")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelIcon(PixelGlyph.Cross, size = 28.dp)
            CofreText(stringResource(R.string.unsuitable_title), CofreTheme.type.title2)
        }
        CofreText(stringResource(R.string.unsuitable_body), CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
        ListSection(label = stringResource(R.string.unsuitable_missing)) {
            val missing = readiness.vulkan.error?.let { listOf(resources.getString(R.string.vk_none)) } ?: readiness.vulkan.missing.map { plainVulkan(resources, it) }
            missing.forEach { BlockRow(it, leading = { PixelIcon(PixelGlyph.Cross, size = 14.dp) }) }
        }
        ListSection(label = stringResource(R.string.unsuitable_can_do)) {
            listOf(R.string.unsuitable_can_instances, R.string.unsuitable_can_mods, R.string.unsuitable_can_worlds).forEach {
                BlockRow(stringResource(it), leading = { PixelIcon(PixelGlyph.Check, size = 14.dp) })
            }
        }
        ListSection(label = stringResource(R.string.unsuitable_device)) {
            CheckRow(stringResource(R.string.setup_java), stringResource(if (readiness.javaBundled) R.string.setup_java_ready else R.string.setup_java_missing), readiness.javaBundled)
            CheckRow(stringResource(R.string.setup_storage), stringResource(R.string.setup_storage_free, Fmt.bytes(context, readiness.freeBytes)), readiness.storageOk)
        }
        if (readiness.vulkan.deviceName.isNotBlank()) {
            Note("${readiness.vulkan.deviceName} · Vulkan ${readiness.vulkan.apiVersion} · ${readiness.vulkan.missing.joinToString()}")
        }
        BlockButton(stringResource(R.string.unsuitable_continue), onContinue, Modifier.testTag("setup.next"), role = ButtonRole.Primary, size = ButtonSize.Large)
    }
}

/** A Vulkan requirement as a person would say it. */
fun plainVulkan(resources: Resources, requirement: String): String = resources.getString(
    when (requirement) {
        "Vulkan 1.2" -> R.string.vk_version
        "VK_KHR_dynamic_rendering" -> R.string.vk_dynamic_rendering
        "VK_KHR_push_descriptor" -> R.string.vk_push_descriptor
        "VK_KHR_synchronization2" -> R.string.vk_synchronization2
        "VK_EXT_vertex_attribute_divisor" -> R.string.vk_divisor
        "VK_KHR_swapchain" -> R.string.vk_swapchain
        "multiDrawIndirect" -> R.string.vk_multidraw
        "fillModeNonSolid" -> R.string.vk_wireframe
        "samplerAnisotropy" -> R.string.vk_anisotropy
        "shaderDrawParameters" -> R.string.vk_draw_parameters
        "timelineSemaphore" -> R.string.vk_timeline
        else -> return requirement
    },
)

@Composable
private fun SignInStep(graph: AppGraph, onNext: () -> Unit) {
    CenteredCard {
        CofreText(stringResource(R.string.setup_signin_title), CofreTheme.type.title)
        Steps(SetupStep.SignIn)
        CofreText(stringResource(R.string.setup_signin_body), CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
        AccountsPanel(graph.accounts)
        BlockButton(
            stringResource(if (graph.accounts.ready) R.string.setup_continue else R.string.setup_skip),
            onNext,
            Modifier.testTag("setup.next"),
            role = if (graph.accounts.ready) ButtonRole.Primary else ButtonRole.Secondary,
        )
    }
}

@Composable
private fun Ready(
    readiness: DeviceReadiness,
    signedIn: Boolean,
    onDone: () -> Unit,
) {
    CenteredCard {
        CofreText(stringResource(R.string.setup_ready_title), CofreTheme.type.title)
        Steps(SetupStep.Ready)
        ListSection {
            CheckRow(stringResource(R.string.setup_vulkan), stringResource(if (readiness.vulkan.suitable) R.string.setup_ok else R.string.setup_vulkan_no), readiness.vulkan.suitable)
            CheckRow(stringResource(R.string.setup_account), stringResource(if (signedIn) R.string.setup_signed_in else R.string.setup_signed_out), signedIn)
        }
        CofreText(stringResource(R.string.setup_ready_body), CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
        BlockButton(stringResource(R.string.setup_open), onDone, Modifier.testTag("setup.next"), role = ButtonRole.Primary, size = ButtonSize.Large)
    }
}

@Composable
private fun CheckRow(
    title: String,
    detail: String,
    ok: Boolean,
) {
    BlockRow(
        title,
        subtitle = detail,
        leading = { Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { PixelIcon(if (ok) PixelGlyph.Check else PixelGlyph.Cross, size = 14.dp) } },
        trailing = { Meta(stringResource(if (ok) R.string.setup_ok else R.string.setup_check), tone = if (ok) Tone.Success else Tone.Warn) },
    )
}
