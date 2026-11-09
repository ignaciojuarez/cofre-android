package com.ignacio.cofre.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.BuildConfig
import com.ignacio.cofre.R
import com.ignacio.cofre.accounts.Accounts
import com.ignacio.cofre.design.Badge
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.ui.common.ConfirmSheet

/** The account list, Microsoft sign-in, and (debug builds) the offline Dev account. Used in setup and Settings. */
@Composable
fun AccountsPanel(
    accounts: Accounts,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!accounts.configured) {
            Banner(stringResource(R.string.signin_not_configured), PixelGlyph.Key, Modifier.testTag("signin.notConfigured"), tone = Tone.Warn)
        }
        when (val state = accounts.signIn) {
            is Accounts.SignIn.Failed -> Banner(state.message, PixelGlyph.Cross, tone = Tone.Danger) {
                BlockButton(stringResource(R.string.dismiss), accounts::dismissError, size = ButtonSize.Small)
            }
            Accounts.SignIn.Working -> BlockRow(stringResource(R.string.signin_working), leading = { Spinner() })
            Accounts.SignIn.Waiting -> BlockRow(stringResource(R.string.signin_waiting), leading = { Spinner() })
            Accounts.SignIn.Idle -> Unit
        }
        if (accounts.entries.isNotEmpty()) {
            ListSection {
                accounts.entries.forEach { entry ->
                    AccountRow(
                        entry,
                        onSelect = { accounts.select(entry.account.id) },
                        onSignIn = { accounts.begin(context) },
                        onRemove = { removing = entry.account.id },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BlockButton(
                stringResource(R.string.accounts_sign_in),
                { accounts.begin(context) },
                Modifier.weight(1f).testTag("accounts.signIn"),
                role = if (accounts.entries.isEmpty()) ButtonRole.Primary else ButtonRole.Secondary,
                icon = PixelGlyph.Head,
                enabled = accounts.configured,
            )
            if (BuildConfig.DEBUG && accounts.entries.none { it.offline }) {
                BlockButton(stringResource(R.string.accounts_dev), accounts::addDevAccount, Modifier.weight(1f).testTag("accounts.dev"))
            }
        }
    }
    removing?.let { id ->
        val name = accounts.entries.firstOrNull { it.account.id == id }?.account?.name.orEmpty()
        ConfirmSheet(
            stringResource(R.string.accounts_remove_title, name),
            stringResource(R.string.accounts_remove_body),
            stringResource(R.string.remove),
            onConfirm = { accounts.remove(id) },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun AccountRow(
    entry: Accounts.Entry,
    onSelect: () -> Unit,
    onSignIn: () -> Unit,
    onRemove: () -> Unit,
) {
    val account = entry.account
    BlockRow(
        account.name,
        subtitle = when {
            entry.signedOut -> stringResource(R.string.accounts_signed_out)
            entry.offline -> stringResource(R.string.accounts_offline)
            else -> stringResource(R.string.accounts_microsoft)
        },
        selected = account.active,
        onClick = onSelect,
        leading = { PixelIcon(PixelGlyph.Head, size = 22.dp) },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (account.active) Badge(stringResource(R.string.accounts_active), tone = Tone.Success)
                if (entry.signedOut) BlockButton(stringResource(R.string.accounts_sign_in_again), onSignIn, role = ButtonRole.Primary, size = ButtonSize.Small)
                BlockButton(stringResource(R.string.remove), onRemove, role = ButtonRole.GhostDanger, size = ButtonSize.Small)
            }
        },
    )
}
