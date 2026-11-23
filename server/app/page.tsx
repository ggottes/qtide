import { cookies } from 'next/headers'
import { COOKIE, cookie, current, newId, save } from '@/lib/listener'
import { sign } from '@/lib/crypto'
import { seal } from '@/lib/crypto'

/**
 * The site: where a listener signs in with their own account and ticks the box that lets this
 * server spend it. Nothing here is a shared account — it is their key, on their row.
 */
export const dynamic = 'force-dynamic'

async function signIn(form: FormData) {
  'use server'
  const jar = await cookies()
  const id = newId()
  const name = String(form.get('name') ?? '').trim().slice(0, 40) || 'Listener'
  const qobuz = String(form.get('qobuz') ?? '').trim()
  const tidal = String(form.get('tidal') ?? '').trim()
  const shared = form.get('shared') === 'on'
  await save(id, {
    name,
    qobuz: qobuz ? seal(qobuz) : undefined,
    tidal: tidal ? seal(tidal) : undefined,
    shared: shared ? '1' : undefined,
  })
  jar.set(cookie(COOKIE, sign(id)))
}

async function update(form: FormData) {
  'use server'
  const found = await current()
  if (!found) return
  const qobuz = String(form.get('qobuz') ?? '').trim()
  const tidal = String(form.get('tidal') ?? '').trim()
  if (qobuz) found.listener.qobuz = seal(qobuz)
  if (tidal) found.listener.tidal = seal(tidal)
  found.listener.shared = form.get('shared') === 'on' ? '1' : undefined
  await save(found.id, found.listener)
}

async function signOut() {
  'use server'
  const found = await current()
  if (found) await save(found.id, {})
  const jar = await cookies()
  jar.set(cookie(COOKIE, '', 0))
}

export default async function Page() {
  const found = await current()
  const origin = process.env.NEXT_PUBLIC_SITE_URL || ''

  if (!found) {
    return (
      <>
        <img className="logo" alt="Qtide" src="https://cdn.jsdelivr.net/gh/ggottes/qtide@main/assets/banner/banner.png" />
        <h1>Sign in to your Qtide pool</h1>
        <p className="lede">
          Your own Tidal and Qobuz account, used by your own Mochy. Nothing is shared with anyone
          else: this server keeps only your key, encrypted, and serves songs to the module you add
          here.
        </p>
        <form className="card" action={signIn}>
          <label htmlFor="name">A name for you</label>
          <input id="name" name="name" placeholder="Your name" autoComplete="off" />
          <h2>Qobuz user token</h2>
          <p className="muted">
            play.qobuz.com → devtools → Network → any request to <code>www.qobuz.com/api.json</code> →
            copy the <code>X-User-Auth-Token</code> header.
          </p>
          <input name="qobuz" placeholder="X-User-Auth-Token" autoComplete="off" spellCheck={false} />
          <h2>Tidal refresh token</h2>
          <p className="muted">
            Either paste one, or finish a device sign-in below. Both end up in the same box.
          </p>
          <input name="tidal" placeholder="refresh token" autoComplete="off" spellCheck={false} />
          <h2>Allow this server</h2>
          <label className="row" htmlFor="shared">
            <input id="shared" name="shared" type="checkbox" />
            <span>Let my Mochy module play from this account</span>
          </label>
          <div style={{ marginTop: 18 }}>
            <button type="submit">Save and sign in</button>
          </div>
        </form>
      </>
    )
  }

  const has = (v?: string) => (v ? 'saved' : 'not added')
  return (
    <>
      <img className="logo" alt="Qtide" src="https://cdn.jsdelivr.net/gh/ggottes/qtide@main/assets/banner/banner.png" />
      <h1>Hi, {found.listener.name || 'listener'}</h1>
      <p className="lede">
        Qobuz: <strong>{has(found.listener.qobuz)}</strong> · Tidal: <strong>{has(found.listener.tidal)}</strong>
        {found.listener.plan ? <> · plan <strong>{found.listener.plan}</strong></> : null}
        {found.listener.country ? <> · {found.listener.country}</> : null}
      </p>

      <div className="card">
        <h2 style={{ marginTop: 0 }}>Add this server in Mochy</h2>
        <p className="muted">Qtide — Pool → add a server → paste this link:</p>
        <code>{origin || 'https://your-pool.vercel.app'}</code>
      </div>

      <form className="card" action={update}>
        <h2 style={{ marginTop: 0 }}>Replace a key</h2>
        <label htmlFor="qobuz">New Qobuz token</label>
        <input id="qobuz" name="qobuz" placeholder="leave empty to keep" autoComplete="off" spellCheck={false} />
        <label htmlFor="tidal" style={{ marginTop: 14 }}>New Tidal refresh token</label>
        <input id="tidal" name="tidal" placeholder="leave empty to keep" autoComplete="off" spellCheck={false} />
        <label className="row" htmlFor="shared" style={{ marginTop: 16 }}>
          <input id="shared" name="shared" type="checkbox" defaultChecked={found.listener.shared === '1'} />
          <span>Let my Mochy module play from this account</span>
        </label>
        <div style={{ marginTop: 16 }}><button type="submit">Save</button></div>
      </form>

      <form action={signOut}>
        <button className="ghost" type="submit">Sign out and forget my keys</button>
      </form>
    </>
  )
}
