package com.qtide

/**
 * One line for the module page: the service, then whoever and whatever plan it could name.
 *
 * Mochy shows an [com.mochyy.mochymusic.module.AccountState.SignedIn] label in the module's Info
 * card, so everything worth seeing about the account goes in here — there is no other place on the
 * page that a module can write to once it is signed in.
 */
internal fun accountLabel(service: String, vararg parts: String?): String {
    val rest = parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" · ")
    return if (rest.isEmpty()) service else "$service · $rest"
}
