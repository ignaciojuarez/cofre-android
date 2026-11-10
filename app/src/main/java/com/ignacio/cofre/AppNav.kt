package com.ignacio.cofre

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ignacio.cofre.design.ShellSection

/** Where the user is: the shell section, each section's selection and the open sheets (SwiftUI's `NavModel`). */
class AppNav {
    var section by mutableStateOf(ShellSection.Play)
    /** The instance open in Instances (the detail column). */
    var instance by mutableStateOf<String?>(null)
    var settingsPane by mutableStateOf<SettingsPane?>(null)
    var newInstance by mutableStateOf(false)
    var downloads by mutableStateOf(false)

    fun openInstance(id: String) {
        instance = id
        section = ShellSection.Instances
    }

    fun openSettings(pane: SettingsPane) {
        settingsPane = pane
        section = ShellSection.Settings
    }
}

enum class SettingsPane(@param:StringRes val title: Int, val debugOnly: Boolean = false) {
    Game(R.string.settings_game),
    Controller(R.string.settings_controller),
    Accounts(R.string.settings_accounts),
    CurseForge(R.string.settings_curseforge),
    Storage(R.string.settings_storage),
    Logs(R.string.settings_logs),
    About(R.string.settings_about),
    Licenses(R.string.settings_licenses),
    Gallery(R.string.settings_gallery, debugOnly = true),
}
