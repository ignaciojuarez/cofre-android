package com.ignacio.cofre.runtime.debug

import com.ignacio.cofre.runtime.launch.GameAccount
import java.util.UUID

/**
 * Debug builds only (this source set never reaches release): an offline account, so the launch
 * pipeline is testable before Microsoft sign-in. Servers in online mode reject it.
 */
object DevAccount {
    val account: GameAccount = GameAccount(
        username = "Dev",
        uuid = UUID.nameUUIDFromBytes("OfflinePlayer:Dev".toByteArray()).toString().replace("-", ""),
    )
    const val TOKEN = "0"
}
