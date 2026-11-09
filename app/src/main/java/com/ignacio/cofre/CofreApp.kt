package com.ignacio.cofre

import android.app.Application

class CofreApp : Application() {
    /** Built in the main process only: `:game` and `:installer` never touch accounts, the vault or instances. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (getProcessName() == packageName) graph = AppGraph(this)
    }
}
