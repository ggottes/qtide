/**
 * Where a listener's encrypted keys live.
 *
 * Upstash Redis over its REST API: no npm package, just two env vars, and Vercel's free tier is
 * plenty for a personal pool. The records hold sealed strings only (see crypto.ts), so a leaked
 * database is useless without KEY_SECRET.
 */
const url = process.env.UPSTASH_REDIS_REST_URL
const token = process.env.UPSTASH_REDIS_REST_TOKEN

export type Listener = {
  /** what the site calls them; blank until they save the form */
  name?: string
  /** sealed Tidal refresh token, when they added one */
  tidal?: string
  /** sealed Qobuz user token */
  qobuz?: string
  /** "1" once they ticked the box that lets this server use their account */
  shared?: string
  /** what their Qobuz account said last time it was checked */
  plan?: string
  country?: string
}

// dev without Upstash: a map on the global object, so every route bundle in `next dev` sees the same
// one (each route is its own module instance, so a plain const would be one map per route)
const local: Map<string, string> =
  (globalThis as any).__qtideLocal ?? ((globalThis as any).__qtideLocal = new Map<string, string>())

function need(): [string, string] | null {
  if (!url || !token) return null
  return [url.replace(/\/$/, ''), token]
}

async function call(command: unknown[]): Promise<any> {
  const remote = need()
  if (!remote) {
    if (process.env.NODE_ENV === 'production') throw new Error('UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN must be set')
    const [verb, k, v] = command as [string, string, string?]
    if (verb === 'SET') local.set(k, v ?? '')
    if (verb === 'DEL') local.delete(k)
    return verb === 'GET' ? local.get(k) ?? null : 'OK'
  }
  const [base, auth] = remote
  const res = await fetch(base, {
    method: 'POST',
    headers: { authorization: `Bearer ${auth}`, 'content-type': 'application/json' },
    body: JSON.stringify(command),
    cache: 'no-store',
  })
  if (!res.ok) throw new Error(`store answered ${res.status}`)
  const body = await res.json()
  if (body.error) throw new Error(String(body.error))
  return body.result
}

const key = (id: string) => `qtide:listener:${id}`

export async function read(id: string): Promise<Listener | null> {
  const raw = await call(['GET', key(id)])
  if (!raw) return null
  try {
    return JSON.parse(raw) as Listener
  } catch {
    return null
  }
}

export async function write(id: string, listener: Listener): Promise<void> {
  await call(['SET', key(id), JSON.stringify(listener)])
}

export async function remove(id: string): Promise<void> {
  await call(['DEL', key(id)])
}
