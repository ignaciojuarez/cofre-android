package com.ignacio.cofre.accounts

import com.ignacio.cofre.kit.accounts.AccountError
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
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
}
