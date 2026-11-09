package com.ignacio.cofre.ui.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.Field
import com.ignacio.cofre.design.FieldKind
import com.ignacio.cofre.design.IconButton
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.Meta
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.servers.MinecraftLaunchArguments
import com.ignacio.cofre.launch.Launcher
import com.ignacio.cofre.ui.common.ConfirmSheet
import com.ignacio.cofre.ui.common.Note
import com.ignacio.cofre.ui.common.WorldIcon

/** Saved servers for the instance, pinged, with Join (quick play), Add and Remove. */
@Composable
fun ServersList(
    graph: AppGraph,
    instance: Instance,
    modifier: Modifier = Modifier,
) {
    val model = remember(instance.id) { ServersModel(graph, instance) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(model) { model.refresh() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        model.error?.let { Banner(it, PixelGlyph.Cross, tone = Tone.Danger) }
        ListSection(label = stringResource(R.string.servers_title)) {
            if (model.servers.isEmpty()) Note(stringResource(R.string.servers_empty))
            model.servers.forEach { server ->
                val remove = stringResource(R.string.remove)
                BlockRow(
                    server.record.name,
                    Modifier.controllerActions(ControllerAction(Pad.X, remove) { removing = server.id }),
                    subtitle = status(model.pings[server.id]) ?: server.record.ip,
                    leading = { WorldIcon((model.pings[server.id] as? ServersModel.Ping.Online)?.status?.faviconPNG ?: server.record.iconPNG) },
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (model.pings[server.id] == ServersModel.Ping.Pinging) Spinner()
                            BlockButton(
                                stringResource(R.string.servers_join),
                                {
                                    val address = server.address ?: return@BlockButton
                                    graph.launcher.play(instance, Launcher.QuickPlay(MinecraftLaunchArguments.server(address), server.record.name))
                                },
                                role = ButtonRole.Primary,
                                size = ButtonSize.Small,
                                enabled = server.address != null,
                            )
                            IconButton(PixelGlyph.Cross, remove, { removing = server.id })
                        }
                    },
                )
            }
        }
        BlockButton(stringResource(R.string.servers_add), { adding = true }, Modifier.testTag("servers.add"), icon = PixelGlyph.Cmd)
    }
    if (adding) AddServerSheet(onAdd = model::add, onDismiss = { adding = false })
    removing?.let { id ->
        val name = model.servers.firstOrNull { it.id == id }?.record?.name.orEmpty()
        ConfirmSheet(
            stringResource(R.string.servers_remove_title, name),
            stringResource(R.string.servers_remove_body),
            stringResource(R.string.remove),
            onConfirm = { model.servers.firstOrNull { it.id == id }?.let(model::remove) },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun status(ping: ServersModel.Ping?): String? = when (ping) {
    is ServersModel.Ping.Online -> {
        val players = ping.status.online?.let { online -> ping.status.max?.let { "$online/$it" } ?: "$online" }
        listOfNotNull(players?.let { stringResource(R.string.servers_players, it) }, "${ping.status.latencyMillis} ms").joinToString(" · ")
    }
    ServersModel.Ping.Offline -> stringResource(R.string.servers_offline)
    else -> null
}

@Composable
private fun AddServerSheet(onAdd: (String, String) -> Boolean, onDismiss: () -> Unit) {
    val name = rememberTextFieldState()
    val address = rememberTextFieldState()
    BlockSheet(onDismiss, hugsContent = true) {
        SheetHeader(stringResource(R.string.servers_add), onDismiss)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(name, stringResource(R.string.servers_name))
            Field(address, stringResource(R.string.servers_address), kind = FieldKind.Mono, placeholder = "play.example.net")
            BlockButton(
                stringResource(R.string.servers_save),
                { if (onAdd(name.text.toString(), address.text.toString())) onDismiss() },
                role = ButtonRole.Primary,
                enabled = address.text.isNotBlank(),
            )
            Meta(stringResource(R.string.servers_add_note))
        }
    }
}
