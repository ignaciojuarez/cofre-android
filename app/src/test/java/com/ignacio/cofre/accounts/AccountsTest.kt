package com.ignacio.cofre.accounts

import android.net.Uri
import com.ignacio.cofre.Samples
import com.ignacio.cofre.kit.accounts.AccountError
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountsTest {
    @Test fun onlyMicrosoftRefusingTheTokenSignsOut() {
        assertTrue(Accounts.refreshRejected(AccountError.Http("Microsoft", 400)))
        assertTrue(Accounts.refreshRejected(AccountError.Http("Microsoft", 401)))
        for (e in listOf(AccountError.Http("Microsoft", 429), AccountError.Http("Microsoft", 503), AccountError.Http("Xbox", 401), AccountError.Http("Minecraft", 500))) {
            assertFalse(Accounts.refreshRejected(e), "$e keeps the refresh token")
        }
    }

    @Test fun aRedirectWithAnotherStateLeavesTheSignInWaiting() {
        val graph = Samples.graph(clientId = "client")
        graph.vault.set(Accounts.PENDING_STATE, "sent")
        graph.vault.set(Accounts.PENDING_VERIFIER, "v".repeat(43))
        graph.accounts.complete(Uri.parse("cofre://auth?error=access_denied&state=other"))
        graph.accounts.complete(Uri.parse("cofre://auth?code=x"))
        assertEquals("sent", graph.vault.get(Accounts.PENDING_STATE))
        assertEquals(Accounts.SignIn.Idle, graph.accounts.signIn)
        graph.accounts.complete(Uri.parse("cofre://auth?error=access_denied&state=sent"))
        assertNull(graph.vault.get(Accounts.PENDING_STATE))
        assertTrue(graph.accounts.signIn is Accounts.SignIn.Failed)
    }
}
