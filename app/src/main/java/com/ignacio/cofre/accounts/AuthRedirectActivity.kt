package com.ignacio.cofre.accounts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ignacio.cofre.CofreApp
import com.ignacio.cofre.MainActivity

/** Receives Microsoft's `cofre://auth` redirect, hands the code to [Accounts], and returns to Cofre. */
class AuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { (application as CofreApp).graph.accounts.complete(it) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
