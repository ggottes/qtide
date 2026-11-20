# Getting each token

## Tidal — refresh token

Sign in at [tidal.com](https://tidal.com) and take the session's refresh token, or reuse one from a
downloader you already use (streamrip keeps it as `refresh_token`, tiddl as the device-login result).
The module renews the access token itself; a refresh token lasts until the account signs out
everywhere.

A refresh token is **a password for the account**: keep it out of anything you share.

## Qobuz — user token

1. Open [play.qobuz.com](https://play.qobuz.com) and sign in.
2. Developer tools → Network → reload → any request to `www.qobuz.com/api.json`.
3. Copy the request header `X-User-Auth-Token`.

The app id and secret are read from Qobuz's own player script, so they are not asked for. If you have
your own pair, put `appId:secret` in the optional field.

## Deezer — ARL cookie

1. Open [deezer.com](https://www.deezer.com) and sign in.
2. Developer tools → Application → Cookies → `https://www.deezer.com`.
3. Copy the `arl` cookie's value (about 190 characters).

An account without HiFi plays MP3 320; with HiFi it plays FLAC.

## Where they are kept

In the module's own storage on the phone, once, when you press away from the field. They are sent
only to that service. Removing the module deletes them with it.
