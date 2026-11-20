# Signing in

Every module has a **Sign in** button on its page. Signing in is the easy path; a token pasted into
the field is still there for anyone who already has one.

## Tidal

Tap **Sign in** and Mochy's own browser opens [link.tidal.com](https://link.tidal.com). Log in there,
then play any song once: the module's step line shows a code like `approve code OONFC`, and typing it
at link.tidal.com finishes the sign-in. The play after that is the first one that streams; the module
keeps the refresh token it was handed and renews the access token itself.

A refresh token can still be pasted instead (streamrip keeps one as `refresh_token`, tiddl gives one at
the end of its device login). Either way it is **a password for the account**: keep it out of anything
you share.

## Qobuz

Tap **Sign in**; the browser opens play.qobuz.com's login in its desktop layout (the player refuses to
render narrow). After you log in, the first request the page makes to `www.qobuz.com/api.json` carries
the `X-User-Auth-Token` header, Mochy hands it to the module, and the sheet closes with the account in.

By hand: developer tools → Network → any request to `www.qobuz.com/api.json` → copy
`X-User-Auth-Token`. The app id and secret are read from Qobuz's own player script, so they are not
asked for; if you have your own pair, put `appId:secret` in the optional field.

## Deezer

The ARL is an `HttpOnly` cookie, which no browser hands to an app. **Open Deezer** on the module's
page takes you to the site (handy on a phone with no devtools), but the cookie itself has to be copied
somewhere it can be read:

1. Open [deezer.com](https://www.deezer.com) on a computer, signed in.
2. Developer tools → Application → Cookies → `https://www.deezer.com`.
3. Copy the `arl` cookie's value (about 190 characters) into the module's field.

A Deezer Free account has no streaming, so the module says so instead of playing a preview. HiFi
plays FLAC; everything else plays MP3.

## Where they are kept

In the module's own storage on the phone. They are sent only to that service. **Sign out** clears them,
and removing the module deletes them with it.
