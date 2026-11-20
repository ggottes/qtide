package com.qtide

import com.mochyy.mochymusic.module.ModuleHost

/**
 * The parts of signing in that all three modules share: what the sign-in browser sends back, and
 * who is signed in once a token or a cookie is on its way.
 *
 * Mochy opens the module's [com.mochyy.mochymusic.module.SignInSpec] page in its own web view and
 * hands every request header the service's page sends to `onSignInHeader`. Tidal needs no header
 * (its refresh token comes from a device login), Qobuz's token is one header, and Deezer's ARL is a
 * cookie the app never forwards, so its page is only a shortcut to the site.
 */
internal object SignIn {

    /** the Qobuz user token the web player sends on its own api calls */
    private const val QOBUZ_TOKEN = "x-user-auth-token"
    private const val QOBUZ_APP = "x-app-id"

    /**
     * Qobuz: the browser's token fills the same storage slot the text field writes, so the page
     * shows it and `login()` picks it up unchanged.
     *
     * @return true once a token is stored, which is what closes mochy's sign-in sheet
     */
    fun qobuz(host: ModuleHost, name: String, value: String): Boolean {
        val trimmed = value.trim()
        when (name.trim().lowercase()) {
            QOBUZ_TOKEN -> if (trimmed.length >= 20) host.storage.set("userToken", trimmed)
            QOBUZ_APP -> if (trimmed.isNotEmpty()) host.storage.set("appPair", "$trimmed:")
        }
        return !host.storage.get("userToken").isNullOrBlank()
    }
}
