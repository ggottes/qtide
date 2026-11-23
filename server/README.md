# Qtide pool server

One Next.js app on Vercel that holds each listener's **own** Tidal and Qobuz key, encrypted, and
answers the Qtide — Pool module. Nobody sees anybody else's account: the phone only ever holds a
signed cookie that names its row.

## What it does

| route | for |
| --- | --- |
| `/` | the site: sign in with your own keys, tick the box, get the server link to paste into Mochy |
| `/api/health` | how the module recognises a Qtide server before it keeps it |
| `/api/me` | who this device is signed in as, or 401 |
| `/api/search?q=` | the best recording on **that listener's** account |
| `/api/stream?source=&id=&formats=` | one link the player opens; Qobuz first, always |

## Deploy

1. `vercel` (or the dashboard) with this folder as the root, then set:
   - `KEY_SECRET` — 32 random bytes as hex: `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"`
   - `UPSTASH_REDIS_REST_URL` / `UPSTASH_REDIS_REST_TOKEN` — a free Upstash Redis
   - `NEXT_PUBLIC_SITE_URL` — your deployment's address, shown on the page
2. Open the site, add your Qobuz token (and/or Tidal), tick "let my Mochy module play from this account".
3. In Mochy: Qtide — Pool → add a server → paste the address.

## How the keys are kept

Every token is sealed with AES-256-GCM under `KEY_SECRET` before it reaches the database, and the
cookie is an HMAC-signed row id. A database dump without `KEY_SECRET` is noise. Rotating
`KEY_SECRET` means everyone signs in again.

## Local

```bash
npm install
KEY_SECRET=$(node -e "console.log(require('crypto').randomBytes(32).toString('hex'))") npm run dev
```

Without Upstash the store is an in-memory map, so `npm run dev` works off the box; production
refuses to boot without the two env vars.

## Qobuz first

The pool asks Qobuz for every song before Tidal: one plain FLAC link, the best tier the account's
plan allows, so a Mochy download (which wants one whole file) works. Tidal is the second opinion for
a song Qobuz doesn't have — its hi-res answer is a DASH manifest, which the module passes on as-is.
