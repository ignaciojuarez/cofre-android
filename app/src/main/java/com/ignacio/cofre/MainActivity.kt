package com.ignacio.cofre

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.ignacio.cofre.design.AdaptiveShell
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ControllerHost
import com.ignacio.cofre.design.ShellSection
import com.ignacio.cofre.ui.instances.InstancesSection
import com.ignacio.cofre.ui.mods.ModsSection
import com.ignacio.cofre.ui.play.PlaySection
import com.ignacio.cofre.ui.settings.SettingsSection

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as CofreApp).graph
        setContent { CofreRoot(graph) }
    }
}

@Composable
fun CofreRoot(graph: AppGraph) {
    var section by rememberSaveable { mutableStateOf(ShellSection.Play) }
    CofreTheme {
        ControllerHost(onStart = { section = ShellSection.Play }) {
            AdaptiveShell(section, onSectionChange = { section = it }) { current ->
                when (current) {
                    ShellSection.Play -> PlaySection(graph)
                    ShellSection.Instances -> InstancesSection(graph)
                    ShellSection.Mods -> ModsSection(graph)
                    ShellSection.Settings -> SettingsSection(graph)
                }
            }
        }
    }
}
